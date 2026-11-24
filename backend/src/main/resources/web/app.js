/* API-backed presentation only. No synthetic repositories, findings or scores. */
(() => {
  'use strict';
  const M = window.EvoModel, $ = id => document.getElementById(id);
  const state = { setups: [], connections: [], jobs: [], labels: new Map(), setup: null, job: null, resultJob: null, graph: null, contexts: [], reports: [], context: '', tab: 'overview', epoch: 0, pollGeneration: 0, historyLimit: 8, cy: null, online: false, retry: null, submitting: null };
  let openChatOnFirstResult = new URLSearchParams(location.search).get('view') === 'chat';
  const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text != null) n.textContent = String(text); return n; };
  const append = (parent, ...children) => { children.filter(Boolean).forEach(child => parent.append(child)); return parent; };
  const safe = async action => { try { await action(); } catch (error) { if (error.name !== 'AbortError') notice(error.message, true); } };
  const btn = (text, action, cls = 'button') => { const n = el('button', cls, text); n.type = 'button'; n.addEventListener('click', () => safe(action)); return n; };
  const badge = text => el('span', 'badge' + (/FAILED|CANCELLED|TIMED_OUT|ERROR/i.test(text) ? ' bad' : /PARTIAL|UNKNOWN|MAY|WITHHELD|UNRESOLVED|CANDIDATE/i.test(text) ? ' warn' : /COMPLETE|SUCCEEDED|MUST|RESOLVED|SAT/i.test(text) ? ' good' : ''), text);
  const empty = (title, text) => append(el('div', 'empty'), el('h3', '', title), el('p', '', text));
  const card = (title, hint) => append(el('section', 'surface-section'), append(el('div', 'card-heading'),
    append(el('div'), el('h2', '', title), hint ? el('p', 'muted', hint) : null)));
  function selectControl(label, options, value) {
    const select = el('select'); select.setAttribute('aria-label', label);
    for (const [id, text] of options) { const option = el('option', '', text); option.value = id; select.append(option); }
    if (value != null) select.value = value; return select;
  }
  const field = (label, control) => append(el('label', 'control-field'), el('span', 'small muted', label), control);
  const relationshipKinds = scope => scope === 'SPRING_COMPONENT' ? ['spring.selected-binding'] : scope === 'ENTITY' ? ['java.calls', 'java.constructor-calls', 'java.reads-field', 'java.writes-field', 'java.type-uses', 'java.returns', 'java.field-type', 'java.parameter-type', 'java.throws', 'java.extends', 'java.implements', 'java.method-references', 'java.type-parameter-bound', 'java.type-argument'] : ['graph.depends-on'];
  const defaultDependencyContext = env => env.context === env.snapshot
    ? state.contexts.find(context => context.identity.startsWith('analysis:'))?.identity ?? env.context
    : env.context;
  let graphRenderer, refreshGraphTheme;
  function loadGraphRenderer() {
    if (typeof window.cytoscape === 'function') return Promise.resolve(true);
    return graphRenderer ??= new Promise(resolve => {
      const script = el('script');
      script.src = '/vendor/cytoscape-3.30.2.min.js';
      const unavailable = () => {
        script.remove();
        graphRenderer = null;
        resolve(false);
      };
      script.onload = () => typeof window.cytoscape === 'function' ? resolve(true) : unavailable();
      script.onerror = unavailable;
      document.head.append(script);
    });
  }
  const workspaceTools = {
    '#compare': {
      name: 'Compare snapshots', page: '/evolution.html', style: '/evolution.css',
      scripts: ['/evolution.js'], className: 'evolution-page', mount: 'mountEvoEvolution', capability: 'evolutionEnabled'
    },
    '#bridge-evidence': {
      name: 'Captured connections', page: '/bridges.html', style: '/bridges.css',
      scripts: ['/bridge-model.js', '/bridges.js'], className: 'bridge-page', mount: 'mountEvoBridges', capability: 'bridgesEnabled'
    }
  };
  const toolPanels = new Map();
  function loadToolAsset(tag, path) {
    return new Promise((resolve, reject) => {
      const asset = el(tag);
      if (tag === 'link') { asset.rel = 'stylesheet'; asset.href = path; }
      else asset.src = path;
      asset.onload = resolve;
      asset.onerror = () => { asset.remove(); reject(new Error('A local tool asset could not be loaded.')); };
      document.head.append(asset);
    });
  }
  async function updateWorkspaceTool() {
    if (!state.capabilities) return;
    const selected = workspaceTools[location.hash], panel = $('workbench-tool'), content = $('workbench-tool-content');
    $('workspace-tools').open = false;
    $('repository-workspace').hidden = Boolean(selected);
    panel.hidden = !selected;
    if (!selected) {
      if (state.openTool) $('repository-title').focus({ preventScroll: true });
      state.openTool = null;
      return;
    }
    const previous = state.openTool;
    state.openTool = selected;
    let tool = toolPanels.get(selected);
    if (!tool) {
      tool = el('section', 'embedded-tool ' + selected.className);
      content.append(tool);
      toolPanels.set(selected, tool);
      if (!state.capabilities[selected.capability]) {
        tool.append(empty(selected.name + ' unavailable', 'This Workbench server has no data service for this view.'));
      } else {
        tool.append(empty('Opening ' + selected.name.toLowerCase() + '…', 'Loading the local view.'));
        try {
          const [, source] = await Promise.all([loadToolAsset('link', selected.style),
          fetch(selected.page).then(response => {
            if (!response.ok) throw new Error('The local view could not be loaded.');
            return response.text();
          })]);
          const template = new DOMParser().parseFromString(source, 'text/html');
          const main = template.querySelector('main');
          if (!main) throw new Error('The local view is incomplete.');
          tool.replaceChildren(...main.children, ...template.querySelectorAll('body > dialog'));
          if (!window[selected.mount]) for (const script of selected.scripts) await loadToolAsset('script', script);
          window[selected.mount](tool);
        } catch (error) {
          tool.replaceChildren(empty('Could not open ' + selected.name.toLowerCase(), error.message),
            btn('Retry', () => { toolPanels.delete(selected); tool.remove(); return updateWorkspaceTool(); }));
        }
      }
    }
    for (const [key, item] of toolPanels) item.hidden = key !== state.openTool;
    if (selected !== previous && state.openTool === selected) {
      $('main').scrollTop = 0;
      panel.querySelector('a').focus({ preventScroll: true });
    }
  }
  function digestChip(value) {
    const str = String(value ?? '');
    if (!/^sha256:[0-9a-f]{16,}$/i.test(str)) return el('span', '', str);
    const chip = btn(M.shortDigest(str), async () => {
      try { await navigator.clipboard.writeText(str); chip.textContent = 'Copied!'; setTimeout(() => { chip.textContent = M.shortDigest(str); }, 1200); } catch (_) { }
    }, 'digest-chip');
    chip.title = str + ' (Click to copy full SHA-256 digest)';
    return chip;
  }
  async function resolveLabels(identities, env) {
    const pending = [...new Set(identities)].filter(id => !state.labels.has(id));
    for (let offset = 0; offset < pending.length && !env.signal.aborted; offset += 3) {
      await Promise.allSettled(pending.slice(offset, offset + 3).map(async identity => { const result = await env.query('entity', { identity }); const value = result.items[0]; if (value && !env.signal.aborted) state.labels.set(identity, M.label(value)); }));
    }
  }
  async function configureRules(openEditor = true) {
    if (!state.graph) {
      $('policy-settings').open = true;
      $('connect-error').hidden = true;
      $('connect-dialog').showModal();
      return;
    }
    await labelContext(state.context);
    if (!['analysis.manifest', 'spring.context'].includes(state.contextKinds.get(state.context))) {
      const next = state.contexts.find(context => context.analysis);
      if (next) {
        state.context = next.identity;
        $('context').value = next.identity;
      }
    }
    await loadTab('metrics');
    const editor = $('snapshot-policy-editor');
    if (editor && openEditor) {
      editor.open = true;
      editor.querySelector('textarea').focus();
    }
  }
  function notice(message, error = false) { $('notice').hidden = !message; $('notice').textContent = message || ''; $('notice').classList.toggle('error', error); }
  async function api(path, body, signal, method = (body === undefined ? 'GET' : 'POST'), timeoutMillis = 180000) {
    const timeout = AbortSignal.timeout(timeoutMillis), combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
    let response;
    try { response = await fetch((path.startsWith('/api/') ? '' : '/api/v1') + path, { method, headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: combined }); }
    catch (error) { if (signal?.aborted) throw error; throw new Error(error.name === 'TimeoutError' ? 'The server took too long. Retry or narrow the request.' : 'Cannot reach the analysis server. Check that it is running, then refresh.'); }
    const envelope = await response.json();
    if (!response.ok || envelope.error) {
      throw Object.assign(new Error(envelope.error?.message || M.apiError(envelope.error?.code)),
        { code: envelope.error?.code, status: response.status });
    }
    return envelope.data;
  }
  const repoLabel = id => {
    const conn = state.connections.find(c => c.setup === id);
    const setup = state.setups.find(s => s.id === id);
    return cleanRepoName(id, conn, setup);
  };
  function invalidate() { state.epoch++; state.view?.abort(); state.cy?.destroy(); state.cy = null; refreshGraphTheme = null; closeInspector(); $('view-status').textContent = ''; }
  function clearReadiness() {
    state.readiness?.abort(); state.readiness = null;
    state.readinessStage = '';
    state.buildPreview = null;
  }
  function resetResult() { state.discovery?.abort(); state.publication?.abort(); $('result-opening').hidden = true; clearReadiness(); invalidate(); state.graphFocus = null; state.explorer = null; state.graph = null; state.resultJob = null; state.context = ''; state.contexts = []; state.reports = []; $('analysis-workspace').hidden = true; $('welcome').hidden = false; updateResultStatus(); }
  function showRepositoryPrompt() {
    $('repository-title').textContent = 'Explore your architecture';
    $('welcome').querySelector('h2').textContent = 'Connect a repository';
    $('welcome').querySelector('p').textContent = 'Use a local folder or a Git repository to explore dependencies and findings.';
    $('welcome').querySelector('button').hidden = false;
  }
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
    } else if (state.submitting) {
      message = 'Starting analysis…';
    }
    if ($('result-status').textContent !== message) $('result-status').textContent = message;
  }
  function syncRunControls() {
    const busy = state.submitting || (state.job && !M.terminal(state.job));
    const unavailable = !state.online || !state.setup || Boolean(busy);
    $('run-controls').hidden = !state.setup;
    $('analysis-mode-field').hidden = !state.capabilities?.analysisModes?.includes('FAST_ARCHITECTURE');
    $('analysis-mode').disabled = unavailable;
    $('analysis-mode-note').hidden = $('analysis-mode-field').hidden || $('analysis-mode').value !== 'FAST_ARCHITECTURE';
    $('artifact-recovery-note').hidden = !state.setups.find(setup => setup.id === state.setup)?.artifactPolicy;
    $('analyze').disabled = unavailable;
    $('analyze').textContent = state.retry && !state.submitting ? 'Retry submission' : 'Run analysis';
    $('analyze-force').hidden = !state.capabilities?.analysisResultReuseEnabled ||
      !(state.job?.result?.graph || state.resultJob?.setup === state.setup && state.resultJob?.result?.graph);
    $('analyze-force').disabled = unavailable || Boolean(state.retry);
    $('disconnect-repo').hidden = !state.connections.some(connection => connection.setup === state.setup);
    $('disconnect-repo').disabled = Boolean(busy);
    $('run-options').hidden = $('analysis-mode-field').hidden && $('analyze-force').hidden && $('disconnect-repo').hidden;
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
  function renderSidebar() {
    const repos = $('repositories'), history = $('history');
    repos.replaceChildren();
    if (!state.setups.length) repos.append(el('p', 'muted small', state.online ? 'No repositories yet.' : 'Refresh to reconnect.'));
    for (const setup of state.setups) {
      const conn = state.connections.find(c => c.setup === setup.id);
      const isGit = conn?.kind === 'GIT_HTTPS' || conn?.kind === 'GIT_SSH';
      const name = cleanRepoName(setup.id, conn, setup), active = state.setup === setup.id;
      const row = btn('', () => selectRepo(setup.id), 'sidebar-item repository-row' + (active ? ' active' : ''));
      if (active) row.setAttribute('aria-current', 'true');
      const icon = repoIcon(isGit);
      icon.setAttribute('aria-hidden', 'true');
      const head = append(el('span', 'sidebar-item-head'), icon, el('strong', 'repo-name', name));
      append(row, head, el('small', 'repo-meta', isGit ? 'Git repository' : 'Local folder'));
      repos.append(row);
    }
    history.replaceChildren();
    const jobs = state.jobs.filter(job => !state.setup || job.setup === state.setup);
    if (!jobs.length) history.append(el('p', 'muted small', 'No runs yet.'));
    for (const job of jobs.slice(0, state.historyLimit)) {
      const active = state.job?.id === job.id;
      const row = btn('', () => selectJob(job), 'sidebar-item history-row' + (active ? ' active' : ''));
      if (active) row.setAttribute('aria-current', 'true');
      const date = new Date(job.submittedAt).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
      const jobName = state.setup ? date : repoLabel(job.setup);
      const status = {
        FAILED: 'Failed', CANCELLED: 'Cancelled', TIMED_OUT: 'Timed out',
        QUEUED: 'Queued', RUNNING: 'Running', CANCELLING: 'Cancelling'
      }[job.state];
      append(row, append(el('span', 'sidebar-item-head'), el('strong', 'repo-name', jobName),
        status ? el('span', 'run-state', status) : null));
      if (!state.setup) row.append(el('small', 'repo-meta', date));
      history.append(row);
    }
    $('history-more').hidden = jobs.length <= state.historyLimit;
  }
  async function refreshHistory(more = false) {
    if (!state.online || !state.capabilities?.analysisJobsEnabled) return;
    if (more) { state.historyLimit += 20; renderSidebar(); return; }
    let cursor, jobs = [];
    do { const page = await api('/analysis/jobs/history', { size: 50, ...(cursor ? { cursor } : {}) }); jobs.push(...page.items); cursor = page.continuation; } while (cursor);
    state.jobs = jobs;
    state.jobs.sort((a, b) => b.submittedAt.localeCompare(a.submittedAt));
    renderSidebar();
  }
  async function connectServer() {
    notice('');
    try {
      state.capabilities = await api(''); state.online = true;
      $('connection-status').textContent = 'Local server connected'; $('connection-dot').classList.add('online');
      const [setups, connections] = await Promise.all([
        state.capabilities.analysisJobsEnabled ? api('/analysis/setups') : null,
        state.capabilities.repositoryConnectionsEnabled ? api('/analysis/connections') : null,
        refreshHistory()
      ]);
      state.setups = setups?.items ?? [];
      state.connections = connections?.items ?? [];
      $('view-insights').hidden = !state.capabilities.architectureInsightsEnabled;
      $('tab-architecture-chat').hidden = !state.capabilities.architectureChatEnabled;
      $('open-comparison').hidden = !state.capabilities.evolutionEnabled;
      $('open-bridge-captures').hidden = !state.capabilities.bridgesEnabled;
      $('workspace-tools').hidden = $('open-comparison').hidden && $('open-bridge-captures').hidden;
      updateWorkspaceTool();
      document.querySelectorAll('[data-action=connect]').forEach(n => { n.disabled = !state.capabilities.repositoryConnectionsEnabled; n.title = n.disabled ? 'Repository connections are not enabled by this server.' : ''; });
      if (state.setup && !state.setups.some(setup => setup.id === state.setup)) {
        state.setup = null; state.job = null; resetResult(); $('job-panel').hidden = true;
        showRepositoryPrompt();
      } else if (state.job && !state.jobs.some(job => job.id === state.job.id)) {
        await selectRepo(state.setup);
      }
      let saved; try { saved = localStorage.getItem('evo_repository'); } catch (_) { }
      if (!state.setup && state.setups.some(setup => setup.id === saved)) await selectRepo(saved);
      else if (!state.setup && state.setups.some(setup => setup.id === 'local')) await selectRepo('local');
      else if (!state.setup && state.setups.length === 1) await selectRepo(state.setups[0].id);
      else renderSidebar();
      syncRunControls();
      if (!state.capabilities.analysisJobsEnabled) notice('This server is read-only. Start the workbench server to connect repositories and run analyses.');
    } catch (error) { state.online = false; $('connection-status').textContent = 'Server unavailable'; $('connection-dot').classList.remove('online'); syncRunControls(); renderSidebar(); notice(error.message, true); }
  }
  async function selectRepo(id) {
    stopActivityClock();
    state.setup = id; state.job = null; state.retry = null; state.pollGeneration++; resetResult(); $('job-panel').hidden = true;
    try { localStorage.setItem('evo_repository', id); } catch (_) { }
    $('search').value = ''; state.tab = 'overview'; state.historyLimit = 8;
    $('welcome').querySelector('h2').textContent = 'Ready to inspect your code';
    $('welcome').querySelector('p').textContent = 'Run an analysis to capture the current source, then explore its architecture and evidence.';
    $('welcome').querySelector('button').hidden = true;
    $('repository-title').textContent = repoLabel(id); $('repository-description').textContent = 'Run an analysis to inspect this repository. Results stay linked to their captured source.';
    syncRunControls();
    closeMobileSidebar(); const latest = state.jobs.find(job => job.setup === id); renderSidebar();
    if (latest) await selectJob(latest, true);
  }
  async function connectRepository(event) {
    event.preventDefault(); $('connect-error').hidden = true; $('connect-submit').disabled = true;
    try {
      const policy = $('repository-policy').value;
      const connection = await api('/analysis/connections', { source: $('repository-source').value.trim(), ...(policy.trim() ? { policy } : {}) });
      state.connections = (await api('/analysis/connections')).items; state.setups = (await api('/analysis/setups')).items;
      $('connect-dialog').close(); await selectRepo(connection.setup);
      if (!state.job && state.setup === connection.setup) await safe(() => submit());
    } catch (error) { $('connect-error').textContent = error.message; $('connect-error').hidden = false; $('repository-source').focus(); }
    finally { $('connect-submit').disabled = false; }
  }
  let pendingDisconnectSetup = null;
  function promptDisconnect(setupId) {
    pendingDisconnectSetup = setupId;
    $('disconnect-repo-name').textContent = repoLabel(setupId);
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
          showRepositoryPrompt();
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
    const mode = $('analysis-mode-field').hidden ? 'FULL' : $('analysis-mode').value;
    const request = state.retry ||= { setup, idempotencyKey: crypto.randomUUID(), ...(force ? { force: true } : {}), ...(mode === 'FAST_ARCHITECTURE' ? { mode: 'FAST_ARCHITECTURE' } : {}) };
    $('run-options').open = false; state.submitting = request; syncRunControls(); updateResultStatus();
    try {
      const job = await api('/analysis/jobs', request, undefined, 'POST', 15000);
      if (state.retry === request) state.retry = null;
      if (state.setup === setup && state.pollGeneration === generation) await selectJob(job, true);
      await refreshHistory();
    } catch (error) {
      if (state.setup === setup && state.pollGeneration === generation) {
        notice(error.code ? M.apiError(error.code) : error.message, true);
      }
    } finally {
      if (state.submitting === request) state.submitting = null;
      syncRunControls(); updateResultStatus();
    }
  }
  async function loadTelemetry() {
    const job = state.job, panel = $('job-timing'), bar = $('job-telemetry-bar');
    if (!job || !panel.open || !M.terminal(job) || state.telemetryJob === job.id) return;
    state.telemetryJob = job.id;
    bar.textContent = 'Loading stage timings…';
    try {
      const telemetry = await api('/analysis/jobs/' + job.id + '/telemetry');
      if (state.job?.id !== job.id) return;
      const total = el('p', 'small', 'Total ' + formatDuration(telemetry.totalElapsedMillis));
      const stages = el('dl', 'job-timing-list');
      for (const { stage, durationMillis } of telemetry.stages) {
        stages.append(el('dt', '', analysisSteps[stage]?.[0] || stage), el('dd', '', formatDuration(durationMillis)));
      }
      bar.replaceChildren(total, stages);
    } catch (error) {
      if (state.job?.id !== job.id) return;
      state.telemetryJob = null;
      bar.replaceChildren(el('p', 'small muted', error.message), btn('Retry timings', loadTelemetry));
    }
  }
  const formatDuration = ms => ms < 1000 ? ms + ' ms' : (ms / 1000).toFixed(1) + ' s';
  const analysisSteps = {
    QUEUED: ['In queue', 'Queued', 'Waiting for an analysis worker.'],
    ACQUISITION: ['Capturing repository', 'Repository captured', 'Recording the files and build inputs for this run.'],
    PLATFORM: ['Preparing analysis', 'Analysis inputs prepared', 'Reading build descriptors, dependency metadata and the selected runtime.'],
    ANALYSIS: ['Analyzing source', 'Source analyzed', 'Reading source files and resolving relationships across the captured contexts.'],
    GRAPH: ['Building dependency graph', 'Dependency graph built', 'Assembling the architecture and its dependencies.'],
    POLICY: ['Checking rules', 'Rules checked', 'Evaluating the configured architecture rules.'],
    PUBLICATION: ['Saving results', 'Results saved', 'Writing and verifying the result for this run.'],
    REUSED: ['Reusing saved results', 'Saved results reused', 'The captured inputs match the saved run.']
  };
  const runOutcomes = {
    PARTIAL: 'Results ready', SUCCEEDED: 'Results ready', EMPTY: 'Repository captured',
    FAILED: 'Analysis failed', CANCELLED: 'Run cancelled', TIMED_OUT: 'Run timed out'
  };
  const failureMessages = {
    ANALYSIS_FAILED: 'The analyzer could not produce a result.',
    RESULT_PUBLICATION_FAILED: 'The run could not save its results. Try running the analysis again.',
    RESULT_STORAGE_FAILED: 'The analysis store could not save the graph. Check that the store is writable and has room.',
    JOB_STORAGE_UNAVAILABLE: 'The run history could not be saved. Check that the analysis store is writable.',
    SEMANTIC_WORKER_REQUIRED: 'This run needs a compatible analysis worker with enough memory.',
    SETUP_CONFIGURATION_CHANGED: 'The repository configuration changed. Start a new run.',
    DEADLINE_EXCEEDED: 'The run reached its execution deadline.',
    PROVIDER_INTERRUPTED: 'The analysis worker stopped before finishing.',
    USER_CANCELLED: 'Cancellation was acknowledged.',
    SERVICE_CLOSED: 'The analysis server shut down during this run.'
  };
  const inputFailureMessages = {
    ROOT_NOT_FOUND: 'The repository folder could not be found. Reconnect using its current path.',
    ROOT_READ_FAILED: 'The repository folder could not be read. Check its permissions.',
    ROOT_NOT_DIRECTORY: 'The selected path is no longer a folder.',
    DIRECTORY_READ_FAILED: 'A directory could not be read. Check its permissions.',
    FILE_READ_FAILED: 'A repository file could not be read. Check its permissions.',
    FILE_CHANGED_DURING_READ: 'A file changed during capture. Retry after writes to the repository finish.',
    DIRECTORY_CHANGED_DURING_READ: 'A directory changed during capture. Retry after writes to the repository finish.'
  };
  const activityTime = ms => {
    const seconds = Math.floor(Math.max(0, ms) / 1000);
    if (seconds < 60) return seconds + ' s';
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    return (hours ? hours + ' h ' : '') + minutes % 60 + ' min ' + String(seconds % 60).padStart(2, '0') + ' s';
  };
  let activityClock, activityPulse;
  function stopActivityClock() {
    clearInterval(activityPulse);
    activityPulse = undefined;
    activityClock = undefined;
  }
  function tickActivity() {
    const clock = activityClock;
    if (!clock || state.job?.id !== clock.id) { stopActivityClock(); return; }
    const delta = clock.running ? performance.now() - clock.observedAt : 0;
    $('job-elapsed').textContent = (clock.stale ? '≈ ' : '') + activityTime(clock.elapsed + delta);
    if (clock.activeStep) clock.activeStep.textContent = (clock.stale ? '≈ ' : '') + activityTime(clock.stageElapsed + delta);
  }
  function syncActivityClock(job) {
    const running = !M.terminal(job);
    const activeStep = running ? $('job-events').querySelector('[aria-current="step"] .step-duration') : null;
    const event = [...(job.events ?? [])].reverse().find(entry => entry.stage === job.stage);
    const stageElapsed = event ? Math.max(0, job.elapsedMillis - (Date.parse(event.at) - Date.parse(job.submittedAt))) : job.elapsedMillis;
    activityClock = { id: job.id, elapsed: job.elapsedMillis, stageElapsed, observedAt: performance.now(), running, activeStep };
    tickActivity();
    if (running && activityPulse === undefined) activityPulse = setInterval(tickActivity, 1000);
    else if (!running) stopActivityClock();
  }
  function renderActivity(job) {
    const list = $('job-events'), events = [...(job.events ?? [])];
    if (M.terminal(job) && events.at(-1)?.stage !== 'FINISHED' && job.finishedAt) {
      events.push({ stage: 'FINISHED', at: job.finishedAt });
    }
    const items = new Map([...list.children].map(item => [item.dataset.event, item]));
    const remaining = new Set(items.values());
    const failed = job.state === 'FAILED' || job.state === 'TIMED_OUT' || job.state === 'CANCELLED';
    const failureCode = job.error || job.stop;
    for (const [index, event] of events.entries()) {
      const key = event.stage + '/' + event.at;
      let item = items.get(key);
      if (!item) {
        const time = el('time', 'step-started', new Date(event.at).toLocaleTimeString());
        time.dateTime = event.at;
        const details = append(el('details'), append(el('summary'),
          el('span', 'step-title'), el('span', 'step-duration')),
          time, el('p', 'step-note'), el('p', 'step-observation'));
        details.dataset.stage = event.stage;
        item = append(el('li'), details);
        item.dataset.event = key;
        list.append(item);
      }
      remaining.delete(item);
      const step = analysisSteps[event.stage], next = events[index + 1], finished = event.stage === 'FINISHED';
      const active = !M.terminal(job) && event.stage === job.stage && !next;
      const interrupted = failed && next?.stage === 'FINISHED';
      if (active) item.setAttribute('aria-current', 'step'); else item.removeAttribute('aria-current');
      item.querySelector('.step-title').textContent = finished ? runOutcomes[job.state] :
        step?.[active || interrupted ? 0 : 1] ?? 'Analysis';
      if (!active) item.querySelector('.step-duration').textContent = next ?
        activityTime(Date.parse(next.at) - Date.parse(event.at)) + (interrupted ? ' before stopping' : '') : '';
      item.querySelector('.step-note').textContent = finished ?
        failureMessages[failureCode] || (failed ? 'The run ended before a result was saved.' : 'Open the overview to explore this run.') :
        step?.[2] ?? 'The server recorded this step.';
      const observation = item.querySelector('.step-observation');
      observation.textContent = event.stage === 'PLATFORM' && state.buildPreview ?
        state.buildPreview.moduleCount + ' build modules recorded' :
        finished && job.error && !failureMessages[job.error] ? 'Error code ' + job.error : '';
      observation.hidden = !observation.textContent;
    }
    for (const item of remaining) item.remove();
  }
  function showProgressConnection(message = '') {
    const status = $('job-connection');
    if (status.textContent !== message) status.textContent = message;
    status.hidden = !message;
  }
  function showRunFailure(job) {
    const node = $('job-failure');
    const failed = job.state === 'FAILED' || job.state === 'TIMED_OUT';
    const message = failed ? failureMessages[job.error || job.stop] || 'The run ended before a result was saved.' : '';
    node.textContent = message;
    node.hidden = !failed;
    if (!failed || !job.result?.inputProblemCount) return;
    const generation = state.pollGeneration;
    api('/analysis/jobs/' + job.id + '/input-diagnostics', { size: 5 }, undefined, 'POST', 8000).then(data => {
      if (!selectedJob(job.id, generation)) return;
      const messages = [...new Set(data.items.map(item => inputFailureMessages[item.problem?.reason]).filter(Boolean))];
      if (messages.length) node.textContent = messages.join(' ');
    }).catch(() => { /* The failure reported by the job remains visible. */ });
  }
  function renderJob(job) {
    $('job-panel').hidden = false; $('welcome').hidden = true;
    const summary = job.state === 'CANCELLING' ? 'Cancelling run' : runOutcomes[job.state] ?? analysisSteps[job.stage]?.[0] ?? 'Analysis in progress';
    if ($('job-description').textContent !== summary) $('job-description').textContent = summary;
    const details = $('run-details');
    if (details.dataset.job !== job.id) {
      details.dataset.job = job.id;
      details.open = job.state === 'FAILED' || job.state === 'TIMED_OUT';
      $('job-events').replaceChildren();
      showProgressConnection();
    }
    const timing = $('job-timing');
    if (timing.dataset.job !== job.id) {
      timing.dataset.job = job.id;
      timing.open = false;
      state.telemetryJob = null;
      $('job-telemetry-bar').replaceChildren();
    }
    timing.hidden = !M.terminal(job);
    $('job-mode').textContent = job.mode === 'FAST_ARCHITECTURE' ? 'Quick analysis · reduced Java detail · installed Node packages inventoried'
      : job.mode === 'FULL' ? 'Deep analysis' : '';
    $('job-mode').hidden = !$('job-mode').textContent;
    if (job.result?.graph) $('repository-description').textContent = 'Captured ' + new Date(job.reuse?.sourceFinishedAt || job.finishedAt || job.submittedAt).toLocaleString() + ' · Run a new analysis after changing the source.';
    $('cancel-job').hidden = M.terminal(job); $('cancel-job').disabled = job.state === 'CANCELLING'; syncRunControls(); updateResultStatus();
    renderActivity(job);
    syncActivityClock(job);
    showRunFailure(job);
    requestScorePreview(job);
  }
  function requestScorePreview(job) {
    const preview = $('job-score-preview');
    const graph = job.result?.graph;
    const active = Boolean(graph || !M.terminal(job) && ['PLATFORM', 'ANALYSIS', 'GRAPH', 'POLICY', 'PUBLICATION'].includes(job.stage));
    if (!active || preview.dataset.job !== job.id) {
      preview.dataset.job = job.id;
      preview.hidden = true;
      preview.textContent = '';
    }
    if (!active || !state.capabilities?.repositoryScoringEnabled) return;
    const key = job.id + '/' + job.stage;
    if (state.scorePreviewRequest === key || !preview.hidden) return;
    state.scorePreviewRequest = key;
    requestRepositoryScore(job.id, graph, savedScorePaths(job.setup)).then(score => {
      if (state.job?.id !== job.id || !$('analysis-workspace').hidden || score.status === 'WITHHELD') return;
      preview.textContent = 'Repository score · ' + (score.score ?? (score.lower + '–' + score.upper)) + ' / 100';
      preview.hidden = false;
    }).catch(() => { /* The inventory may not have sealed yet. A later stage can retry. */ });
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
    if (state.buildPreview || state.readinessStage === job.stage || M.terminal(job) || !['PLATFORM', 'ANALYSIS', 'GRAPH', 'POLICY', 'PUBLICATION'].includes(job.stage)
      || !state.capabilities?.acquisitionRoutes?.some(route => route.endsWith('/overview'))) return;
    state.readiness?.abort();
    state.readinessStage = job.stage;
    const controller = new AbortController(); state.readiness = controller;
    api('/api/v2/acquisitions/' + job.id + '/overview', {}, controller.signal, 'POST', 8000).then(view => {
      if (!selectedJob(job.id, generation) || M.terminal(state.job) || controller.signal.aborted) return;
      state.buildPreview = view;
      renderActivity(state.job);
    }).catch(() => { /* An unpublished preview does not affect the running analysis. */ });
  }
  async function poll(id, generation, since = performance.now(), failures = 0) {
    if (!selectedJob(id, generation)) return;
    let job;
    try {
      job = await api('/analysis/jobs/' + id, undefined, undefined, 'GET', 8000);
    } catch (error) {
      if (!selectedJob(id, generation)) return;
      if (error.code === 'JOB_NOT_FOUND') {
        showProgressConnection('This run is no longer available. You can start a new analysis.');
        state.job = null;
        stopActivityClock();
        $('cancel-job').hidden = true;
        syncRunControls();
        return;
      }
      if (activityClock) {
        activityClock.stale = true;
        tickActivity();
      }
      if (failures >= 1) {
        const age = activityClock ? activityTime(performance.now() - activityClock.observedAt) : '';
        showProgressConnection(age ? 'Waiting for server status. Last confirmed ' + age + ' ago.' : 'Waiting for server status.');
      }
      setTimeout(() => safe(() => poll(id, generation, since, failures + 1)), Math.min(1200 * 2 ** Math.min(4, failures), 12000));
      return;
    }
    if (!selectedJob(id, generation)) return;
    showProgressConnection();
    if (job.revision > state.job.revision || job.revision === state.job.revision && M.terminal(job)) {
      state.job = job;
      state.jobs = state.jobs.map(item => item.id === id ? job : item);
      renderJob(job);
      renderSidebar();
    } else if (job.revision === state.job.revision) syncActivityClock(job);
    if (M.terminal(state.job)) {
      clearReadiness();
      if (state.job.result?.graph) await loadGraph(state.job, id, generation);
      if (selectedJob(id, generation)) await refreshHistory();
      return;
    }
    requestReadiness(state.job, generation);
    const age = performance.now() - since;
    setTimeout(() => safe(() => poll(id, generation, since)), age < 2000 ? 250 : age < 30000 ? 1000 : 2500);
  }
  async function loadGraph(publication, jobId, generation) {
    const { graph, snapshot, contexts } = publication.result;
    const displayed = state.resultJob;
    if (state.graph === graph && displayed && (displayed.id === publication.id ||
      publication.reuse?.sourceJob === (displayed.reuse?.sourceJob ?? displayed.id))) {
      state.resultJob = publication; updateResultStatus(); return;
    }
    state.discovery?.abort();
    const controller = new AbortController(); state.discovery = controller;
    const { signal } = controller;
    const current = () => state.discovery === controller && selectedJob(jobId, generation);
    const loading = $('result-opening');
    let discovery;
    const fromReceipt = typeof snapshot === 'string' && Array.isArray(contexts) && contexts.every(identity => typeof identity === 'string');
    if (fromReceipt) {
      discovery = { snapshot, contexts: contexts.map(identity => ({ identity })) };
    } else {
      loading.replaceChildren(el('p', '', 'Reading the saved result…'));
      loading.hidden = false;
      try {
        discovery = await api('/graphs/' + graph, undefined, signal, 'GET', 30000);
      } catch (error) {
        if (!current() || error.name === 'AbortError') return;
        loading.replaceChildren(el('p', '', 'Could not open these results.'), el('p', 'muted small', error.message),
          btn('Retry opening results', () => loadGraph(publication, jobId, generation)));
        return;
      }
    }
    if (!current()) return;
    loading.hidden = true;
    state.snapshot = discovery.snapshot ?? publication.result.snapshot;

    state.publication?.abort(); state.publication = new AbortController();
    state.graph = graph; state.resultJob = publication; state.contexts = discovery.contexts; state.reports = []; updateResultStatus();
    state.reportIds = [...new Set([...(publication.result.reports ?? []), ...(discovery.reports ?? [])])];
    state.reportIndexLoaded = !fromReceipt;
    state.repositoryScore = null;
    state.policyRuns = new Map();
    state.activePolicyReports = new Map();
    state.reportLoad = null;
    state.moduleLabels = new Map(); state.contextKinds = new Map(); state.contextLoads = new Map(); state.contextLabels = new Map();
    $('analysis-workspace').hidden = false; $('welcome').hidden = true;
    $('job-score-preview').hidden = true;
    $('context').replaceChildren(el('option', '', 'Choose a context…')); $('context').firstChild.value = ''; let index = 0;
    for (const context of state.contexts) {
      const label = context.identity === state.snapshot ? 'Whole repository' : 'Analysis context ' + (++index);
      const option = el('option', '', label);
      option.value = context.identity;
      option.title = context.identity;
      $('context').append(option);
    }
    state.context = M.selectDefaultContext ? M.selectDefaultContext(state.contexts, state.snapshot, state.context)
      : (state.contexts.some(context => context.identity === state.context)
          ? state.context
          : (state.contexts.find(context => context.identity === state.snapshot)?.identity ?? state.contexts[0]?.identity ?? ''));
    $('context').value = state.context;
    void labelContextOptions();
    if (openChatOnFirstResult && state.capabilities?.architectureChatEnabled) {
      openChatOnFirstResult = false;
      await loadTab('architecture-chat');
      return;
    }
    await loadTab(state.tab);
  }
  function applyContextLabel(identity, label) {
    if (!identity || !label) return;
    state.contextLabels?.set(identity, label);
    const mainOption = [...$('context').options].find(n => n.value === identity);
    if (mainOption) mainOption.textContent = label;
    for (const select of document.querySelectorAll('select[aria-label="Graph context"]')) {
      const option = [...select.options].find(n => n.value === identity);
      if (option) option.textContent = identity === state.snapshot ? 'Repository source evidence' : label;
    }
  }
  function labelContext(identity) {
    const graph = state.graph, publication = state.publication, loads = state.contextLoads;
    if (!identity || !publication || state.contextKinds.has(identity)) return;
    if (loads.has(identity)) return loads.get(identity);
    const load = (async () => {
      try {
        const value = await api('/graphs/' + graph + '/queries/provenance', { request: M.request(identity), arguments: {} }, publication.signal);
        if (state.publication !== publication || publication.signal.aborted) return;
        const evidence = value.items?.[0], metadata = evidence?.metadata, build = metadata?.build?.moduleSourceSet;
        if (metadata?.module && metadata.modulePath) state.moduleLabels.set(metadata.module, metadata.modulePath);
        state.contextKinds.set(identity, evidence?.family);
        if (evidence?.family === 'spring.context') {
          document.querySelectorAll('[data-spring-view]').forEach(control => control.hidden = false);
        }
        let label = '';
        if (build) {
          const path = metadata.modulePath || state.moduleLabels.get(build.module) || (build.module?.includes('sha256:') ? null : build.module);
          const module = path === '.' ? 'Root module' : path;
          label = [module || (evidence.family === 'spring.context' ? 'Spring configuration' : 'Java analysis'),
          evidence.family === 'spring.context' && module ? 'Spring' : null, build.sourceSet?.toLowerCase()].filter(Boolean).join(' / ');
        } else if (evidence?.family === 'spring.context') label = 'Spring configuration';
        else if (metadata?.modulePath) label = (metadata.modulePath === '.' ? 'Root module' : metadata.modulePath) + ' / ' + (metadata.sourceSet?.toLowerCase() || 'Java');
        if (label) applyContextLabel(identity, label);
      } catch (_) {
        // Keep the existing explicit context label if provenance is unavailable.
      } finally {
        loads.delete(identity);
      }
    })();
    loads.set(identity, load);
    return load;
  }
  async function labelContextOptions() {
    const publication = state.publication;
    if (!publication || state.contextOptionsLoading === publication) return;
    state.contextOptionsLoading = publication;
    const contexts = state.contexts.filter(context => context.identity !== state.snapshot && !state.contextKinds.has(context.identity));
    for (let i = 0; i < contexts.length && !publication.signal.aborted; i += 2) {
      await Promise.all(contexts.slice(i, i + 2).map(context => labelContext(context.identity)));
    }
    if (state.contextOptionsLoading === publication) state.contextOptionsLoading = null;
  }
  async function loadReports() {
    if (state.reportLoad) return state.reportLoad;
    const graph = state.graph, signal = state.publication?.signal;
    const load = (async () => {
      if (!state.reportIndexLoaded) {
        const discovery = await api('/graphs/' + graph, undefined, signal);
        if (signal?.aborted || graph !== state.graph) return;
        state.reportIds = [...new Set([...state.reportIds, ...(discovery.reports ?? [])])];
        state.reportIndexLoaded = true;
      }
      const ids = state.reportIds;
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
    const graph = state.graph, context = state.context, snapshot = state.snapshot, request = M.request(context), reportIds = state.reports.filter(r => r.context === context);
    return {
      graph, context, snapshot, request, reportIds, signal, setup: state.resultJob?.setup, jobId: state.resultJob?.id,
      contextFamily: state.contextKinds?.get(context), springContext: state.contextKinds?.get(context) === 'spring.context' || context.startsWith('spring-context:'),
      query: (op, args = {}, page, overrides = {}) => api('/graphs/' + graph + '/queries/' + op, { request: { ...request, ...overrides }, arguments: args, ...(page ? { page } : {}) }, signal),
      report: (id, op, args = {}, page) => api('/graphs/' + graph + '/reports/' + id + '/' + op, { arguments: args, ...(page ? { page } : {}) }, signal)
    };
  }
  function skeleton(rows = 3) {
    const wrap = el('div', 'evo-skeleton-stack');
    wrap.setAttribute('aria-hidden', 'true');
    wrap.append(el('i', 'evo-skeleton-bone is-title'));
    for (let i = 0; i < rows; i++) wrap.append(el('i', 'evo-skeleton-bone ' + (i === rows - 1 ? 'is-mid' : 'is-wide')));
    return wrap;
  }
  function renderSourceCodeBlock(data, highlightLine, onPage) {
    const shell = el('div', 'source-viewer-shell');
    const bar = el('div', 'source-viewer-bar');
    const meta = append(el('div'),
      el('div', 'source-viewer-path', data.path || 'Captured file'),
      el('div', 'small muted', [
        data.language || 'text',
        data.startLine && data.endLine ? `Lines ${data.startLine}–${data.endLine} of ${data.totalLines || data.endLine}` : null,
        data.digest ? M.shortDigest(data.digest) : null
      ].filter(Boolean).join(' · '))
    );
    const barActions = el('div', 'remedy-patch-actions');
    const copyBtn = btn('Copy code', async () => {
      try {
        await navigator.clipboard?.writeText(data.content || '');
        copyBtn.textContent = 'Copied';
        window.EvoToast?.push({ kind: 'ok', title: 'Source copied', detail: data.path });
        setTimeout(() => { copyBtn.textContent = 'Copy code'; }, 1400);
      } catch (_) {}
    }, 'button subtle');
    barActions.append(copyBtn);
    if (onPage && data.startLine > 1) {
      barActions.append(btn('← Prev 200', () => onPage(Math.max(1, data.startLine - 200), data.startLine - 1), 'button subtle'));
    }
    if (onPage && data.totalLines && data.endLine < data.totalLines) {
      barActions.append(btn('Next 200 →', () => onPage(data.endLine + 1, Math.min(data.totalLines, data.endLine + 200)), 'button subtle'));
    }
    if (state.capabilities?.architectureChatEnabled && data.path) {
      barActions.append(btn('Ask AI about file', () => {
        closeInspector();
        state.chatSeedPrompt = `Inspect \`${data.path}\` (lines ${data.startLine || 1}–${data.endLine || ''}) and explain its architectural role, dependencies, and any refactoring risks.`;
        return loadTab('architecture-chat');
      }, 'button subtle'));
    }
    bar.append(meta, barActions);
    const pre = el('pre', 'source-viewer-code');
    const lines = String(data.content ?? '').split('\n');
    const start = Number(data.startLine || 1);
    lines.forEach((text, idx) => {
      const ln = start + idx;
      const lineRow = el('div', 'source-line' + (highlightLine && ln === Number(highlightLine) ? ' is-highlight' : ''));
      lineRow.append(el('span', 'source-line-num', String(ln)), el('span', 'source-line-text', text || ' '));
      pre.append(lineRow);
    });
    shell.append(bar, pre);
    return shell;
  }
  function openCapturedSourceModal(graphId, path, startLine = 1, endLine = null, highlightLine = null) {
    const loadRange = (s, e) => inspect(path, 'CAPTURED SOURCE', signal => api('/graphs/' + graphId + '/captured-source', {
      path, startLine: s, ...(e ? { endLine: e } : {})
    }, signal), [], [], undefined, (body, data) => {
      if (!data?.found) {
        body.append(empty(data?.ambiguous ? 'Multiple matching files' : 'Captured source unavailable',
          data?.ambiguous ? 'Choose one of the captured paths below:' : 'This path was not captured as readable source text in the snapshot.'));
        if (Array.isArray(data?.candidates) && data.candidates.length) {
          const list = el('div', 'list');
          data.candidates.forEach(c => list.append(btn(c, () => openCapturedSourceModal(graphId, c, 1, 200), 'entity-row')));
          body.append(list);
        }
        return;
      }
      body.append(renderSourceCodeBlock(data, highlightLine, (ns, ne) => loadRange(ns, ne)));
    });
    return loadRange(startLine, endLine || (startLine + 199));
  }

  function scoreRingSvg(rawScore, label) {
    const num = Number(rawScore);
    const pct = Number.isFinite(num) ? Math.max(0, Math.min(100, num)) : 0;
    const r = 23, c = 2 * Math.PI * r;
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 60 60');
    svg.setAttribute('class', 'score-ring');
    svg.setAttribute('aria-hidden', 'true');
    const track = document.createElementNS(ns, 'circle');
    track.setAttribute('cx', '30'); track.setAttribute('cy', '30'); track.setAttribute('r', String(r));
    track.setAttribute('class', 'score-ring-track');
    const val = document.createElementNS(ns, 'circle');
    val.setAttribute('cx', '30'); val.setAttribute('cy', '30'); val.setAttribute('r', String(r));
    val.setAttribute('class', 'score-ring-value');
    val.setAttribute('stroke-dasharray', String(c));
    val.setAttribute('stroke-dashoffset', String(c * (1 - pct / 100)));
    const text = document.createElementNS(ns, 'text');
    text.setAttribute('x', '30'); text.setAttribute('y', '34');
    text.setAttribute('text-anchor', 'middle');
    text.setAttribute('class', 'score-ring-label');
    text.textContent = label ?? (Number.isFinite(num) ? String(Math.round(pct)) : '—');
    svg.append(track, val, text);
    return svg;
  }

  async function streamArchitectureChat(graphId, payload, signal, handlers) {
    const response = await fetch('/api/v1/graphs/' + graphId + '/architecture-chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(payload),
      signal
    });
    if (!response.ok || !response.body) {
      let msg = 'Architecture chat request failed.';
      try { const err = await response.json(); msg = err?.error?.message || msg; } catch (_) { }
      const e = new Error(msg);
      e.status = response.status;
      throw e;
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let finalResult = null;
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n?/g, '\n');
      let sep;
      while ((sep = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, sep);
        buffer = buffer.slice(sep + 2);
        let eventName = 'message';
        const dataLines = [];
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) eventName = line.slice(6).trim();
          else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
        }
        if (!dataLines.length) continue;
        const parsed = JSON.parse(dataLines.join('\n'));
        if (eventName === 'stage') handlers?.onStage?.(parsed.message || '');
        else if (eventName === 'tool') {
          const kind = M.classifySseToolEvent ? M.classifySseToolEvent(parsed)
            : (parsed.phase === 'start' || parsed.status === 'running' ? 'start' : 'complete');
          if (kind === 'start') handlers?.onToolStart?.(parsed);
          else handlers?.onToolComplete?.(parsed);
        } else if (eventName === 'delta') handlers?.onDelta?.(parsed.text || '');
        else if (eventName === 'complete') finalResult = parsed.data ?? parsed;
        else if (eventName === 'error') throw new Error(parsed.error?.message || parsed.message || 'Architecture chat failed.');
      }
    }
    if (!finalResult) throw new Error('Architecture chat stream ended without a final response.');
    return finalResult;
  }

  function coverageCard(env, result) {
    const section = card('Analysis coverage');
    section.classList.add('job-coverage');
    section.append(el('p', '', result.completion + ' · ' + result.gapCount.toLocaleString() + ' recorded gaps across the run'));
    if (!result.gapCount) return section;
    const body = el('div');
    const details = append(el('details', 'inventory-details'), el('summary', '', 'Gap reasons'), body);
    const status = el('p', 'muted small');
    status.setAttribute('role', 'status');
    section.append(details);
    let requested = false;
    async function read() {
      requested = true;
      body.replaceChildren(status);
      status.textContent = 'Reading the job gap ledger…';
      try {
        const value = await api('/analysis/jobs/' + env.jobId + '/coverage', undefined, env.signal, 'GET');
        if (env.signal.aborted) return;
        status.textContent = value.ledgerTotal
          ? 'Top reasons from ' + value.counted.toLocaleString() + ' of ' + value.ledgerTotal.toLocaleString() + ' job ledger records.'
          : 'The job ledger has no records. This run reports gaps in the graph.';
        if (value.obligations && value.counted > 0) {
          const ob = value.obligations;
          const total = Math.max(1, (ob.environment || 0) + (ob.authority || 0) + (ob.boundary || 0) + (ob.source || 0));
          const barWrap = el('div', 'obligation-bar-wrap');
          const bar = el('div', 'obligation-bar');
          const legend = el('div', 'obligation-legend');
          const items = [
            ['environment', 'Environment', 'is-env'],
            ['authority', 'Authority / policy', 'is-auth'],
            ['boundary', 'Analyzer boundary', 'is-boundary'],
            ['source', 'Source / dynamic', 'is-source']
          ];
          for (const [key, lbl, cls] of items) {
            const count = ob[key] || 0;
            if (!count) continue;
            const seg = el('span', 'obligation-seg ' + cls);
            seg.style.width = ((count / total) * 100).toFixed(1) + '%';
            seg.title = `${lbl}: ${count}`;
            bar.append(seg);
            const dot = el('span', 'obligation-dot ' + cls);
            legend.append(append(el('span', 'obligation-legend-item'), dot, el('span', '', `${lbl}: ${count.toLocaleString()}`)));
          }
          if (bar.childElementCount) {
            barWrap.append(bar, legend);
            body.append(barWrap);
          }
        }
        if (!value.topReasons.length) return;
        const reasons = el('dl', 'summary-table');
        for (const row of value.topReasons) {
          const info = M.humanReason(row.reason);
          const label = row.reason.toLowerCase().replaceAll('_', ' ').replace(/^./, c => c.toUpperCase());
          const term = el('dt', '', label);
          term.title = `${row.reason} — ${info.explanation}`;
          const count = el('dd', '', row.count.toLocaleString());
          reasons.append(append(el('div', 'summary-row'), term, count));
        }
        body.append(reasons);
        const actions = el('div', 'remedy-patch-actions');
        actions.append(btn('Open Gaps & Coverage Inspector →', () => loadTab('coverage'), 'button subtle'));
        if (state.capabilities?.architectureChatEnabled) {
          const topList = value.topReasons.slice(0, 5).map(r => `${r.reason} (${r.count})`).join(', ');
          actions.append(btn('Ask AI how to reduce these gaps', () => {
            state.chatSeedPrompt = 'Explain the top analysis gap reasons in this run (' + topList + ') and recommend prioritized actions to improve coverage.';
            return loadTab('architecture-chat');
          }, 'button subtle'));
        }
        body.append(actions);
      } catch (error) {
        if (!env.signal.aborted) {
          status.textContent = 'Gap reasons are unavailable.';
          body.append(btn('Retry gap reasons', read));
        }
      }
    }
    details.addEventListener('toggle', () => { if (details.open && !requested) read(); });
    return section;
  }
  async function loadTab(tab) {
    const panel = $('panel-' + tab);
    if (!panel) return;
    invalidate(); state.tab = tab; const epoch = state.epoch;
    const group = panel.closest('[data-view-group]');
    const primary = group?.dataset.viewGroup ?? tab;
    for (const control of $('tabs').querySelectorAll('[data-tab]')) {
      const selected = control.dataset.tab === primary;
      control.setAttribute('aria-selected', String(selected));
      control.tabIndex = selected ? 0 : -1;
    }
    $('search-form').hidden = tab !== 'structure';
    for (const option of document.querySelectorAll('.view-links [data-open-tab]')) {
      if (option.dataset.openTab === tab) option.setAttribute('aria-current', 'page');
      else option.removeAttribute('aria-current');
    }
    const updateSpringViews = () => {
      const hasSpringContext = state.contexts.some(c => c.identity.startsWith('spring-context:') || state.contextKinds?.get(c.identity) === 'spring.context');
      const spring = hasSpringContext || state.contextKinds?.get(state.context) === 'spring.context' || ['spring', 'configurations'].includes(tab);
      document.querySelectorAll('[data-spring-view]').forEach(control => control.hidden = !spring);
    };
    updateSpringViews();
    document.querySelectorAll('.analysis-content > [role=tabpanel]').forEach(n => n.hidden = n !== (group || panel));
    document.querySelectorAll('[data-group-panel]').forEach(n => n.hidden = n !== panel);
    panel.hidden = false;

    if (!state.context) { if (panel) { panel.setAttribute('aria-busy', 'false'); panel.replaceChildren(empty('Choose an analysis context', 'Select the module or repository context above. Results are scoped to that selection.')); } return; }
    state.view = new AbortController(); const signal = state.view.signal; if (panel) { panel.setAttribute('aria-busy', 'true'); panel.replaceChildren(empty('Loading…', 'Reading the captured analysis.')); }
    const tabName = $('view-' + tab)?.textContent ?? $('tab-' + tab)?.textContent ?? tab;
    $('view-status').textContent = 'Loading ' + tabName + '…';
    try {
      const reports = ['policy', 'metrics'].includes(tab) ? loadReports() : null;
      if (tab !== 'overview') {
        await labelContext(state.context);
        void labelContextOptions();
      }
      await reports;
      if (epoch !== state.epoch) return;
      updateSpringViews();
      const view = await views[tab](envFor(signal)); if (epoch !== state.epoch) return; panel.replaceChildren(view.node || view); panel.setAttribute('aria-busy', 'false'); $('view-status').textContent = view.status || 'View loaded for the selected analysis context.'; view.after?.();
    }
    catch (error) { if (epoch === state.epoch && error.name !== 'AbortError') { panel.setAttribute('aria-busy', 'false'); panel.replaceChildren(empty('Unable to load this view', error.message), btn('Retry', () => loadTab(tab))); $('view-status').textContent = 'Request failed. Retry to read this analysis.'; } }
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
    const list = el('div', 'list'), footer = el('div', 'page-footer'); footer.setAttribute('role', 'status'); footer.setAttribute('aria-live', 'polite'); let next, items = [], busy = false; container.append(list, footer);
    const more = btn('Load more', () => load(true));
    async function load(continuing = false) {
      if (busy) return; busy = true; more.disabled = true;
      try {
        const result = await fetchPage(continuing ? next : null); if (env.signal.aborted) return;
        if (!continuing) { items = []; list.replaceChildren(); } items.push(...result.items); next = result.continuation;
        if (!items.length) list.append(empty(title || 'No results available', hint || 'Try a different scope or filter.')); else result.items.forEach(item => list.append(onRow(item)));
        footer.replaceChildren(el('span', '', M.pageInfo({ ...result, items }))); if (next) footer.append(more);
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
    try {
      const page = typeof loader === 'function' ? await loader(controller.signal, cursor) : loader; if (controller.signal.aborted) return;
      const value = page?.items && previousItems.length ? { ...page, items: [...previousItems, ...page.items] } : page;
      state.details = value; const body = $('inspector-body'); body.replaceChildren();
      const finish = () => {
        if (actions.length) body.append(append(el('div', 'detail-actions'), ...actions.map(action => btn(action.label, action.run, 'button subtle'))));
        $('download-details').disabled = false;
      };
      if (render) { render(body, value); finish(); return; }
      const fields = el('dl', 'data-fields');
      for (const [key, val] of Object.entries(value || {})) if (val != null && typeof val !== 'object' && !technicalField(key, val) && !['query', 'identity', 'id', 'setup', 'graph', 'snapshot', 'acquisition', 'sourceReport', 'continuation'].includes(key)) {
        const dd = el('dd');
        if (typeof val === 'string' && /^sha256:[0-9a-f]{16,}$/i.test(val)) dd.append(digestChip(val));
        else dd.textContent = String(val);
        append(fields, append(el('div', 'data-field'), el('dt', '', key.replace(/([A-Z])/g, ' $1')), dd));
      }
      body.append(fields);
      if (Array.isArray(value?.items)) {
        const items = value.items.filter(item => item.kind !== 'CAPABILITY_GAP' && !item.gapIdentity);
        for (const [index, item] of items.entries()) {
          const section = el('div', 'evidence-item');
          const itemTitle = item.vertices ? 'Dependency path ' + (index + 1) : item.family || item.kind || 'Evidence ' + (index + 1);
          section.append(el('h3', '', itemTitle));
          if (item.vertices && contextEnv) {
            const feasText = item.feasibility === 'SAT' ? 'Feasible in modeled configurations (SAT)' : item.feasibility === 'UNSAT' ? 'Infeasible / contradictory conditions (UNSAT)' : 'Configuration feasibility: ' + item.feasibility;
            section.append(el('p', 'muted small', item.edges.length + ' dependency steps · ' + feasText));
            const path = el('ol', 'dependency-path');
            item.vertices.forEach((identity, step) => path.append(append(el('li'), btn(state.labels.get(identity) || 'Inspect symbol ' + (step + 1) + ' (' + M.shortDigest(identity) + ')', async () => {
              const entity = await contextEnv.query('entity', { identity });
              if (controller.signal.aborted) return;
              if (entity.items[0]) return inspectEntity(entity.items[0], contextEnv);
              return inspect('Symbol evidence', 'ENTITY', entity);
            }, 'text-button')))); section.append(path);
          } else if (item.span && item.sourceDigest) {
            const span = item.span; section.append(el('p', 'source-coord-pill', (state.labels.get(span.document) || M.shortDigest(span.document) || 'Captured source') + ' · Line ' + span.startLine + ':' + span.startColumn + ' → ' + span.endLine + ':' + span.endColumn));
            const detail = append(el('details'), el('summary', '', 'Source identities & coordinates')); renderFields(detail, item, 0); section.append(detail);
          } else renderFields(section, item.metadata || item, 0);
          body.append(section);
        }
        if (!items.length) body.append(empty('No records returned', ''));
        if (value.continuation && typeof loader === 'function') body.append(btn('Load more evidence', () => inspect(title, kind, loader, actions, value.items, value.continuation)));
      } else renderFields(body, value, 0, true);
      finish();
    } catch (error) { if (!controller.signal.aborted) $('inspector-body').replaceChildren(empty('Details unavailable', error.message)); }
  }
  function technicalField(key, value) {
    return /gap|limitation|diagnostic|^bounds$|^confidence$|^evidenceStatus$|^isTruncated$/i.test(key)
      || /status$/i.test(key) && ['COMPLETE', 'PARTIAL'].includes(value);
  }
  function renderFields(parent, value, depth, objectsOnly = false) {
    if (!value || typeof value !== 'object') return;
    const fields = el('dl', 'data-fields');
    for (const [key, val] of Object.entries(value)) {
      if (val == null || technicalField(key, val) || objectsOnly && typeof val !== 'object') continue;
      if (typeof val !== 'object') {
        const resolved = ['source', 'target'].includes(key) ? state.labels.get(val) || val : val;
        const dd = el('dd');
        if (typeof resolved === 'string' && /^sha256:[0-9a-f]{16,}$/i.test(resolved)) dd.append(digestChip(resolved));
        else dd.textContent = String(resolved);
        append(fields, append(el('div', 'data-field'), el('dt', '', key.replace(/([A-Z])/g, ' $1')), dd));
      } else if (depth < 3 && Object.keys(val).length) {
        const detail = append(el('details'), el('summary', '', key.replace(/([A-Z])/g, ' $1')));
        if (['entity', 'span', 'sourceSpan', 'declaration', 'path', 'dimensions'].includes(key)) detail.open = true;
        renderFields(detail, val, depth + 1); fields.append(detail);
      }
    }
    parent.append(fields);
  }
  function closeInspector() { state.inspector?.abort(); state.inspector = null; if ($('inspector').open) $('inspector').close(); }
  function inspectEntity(item, env) {
    const query = (op, args, signal, override = {}, cursor) => api('/graphs/' + env.graph + '/queries/' + op, { request: { ...env.request, ...override }, arguments: args, ...(['explain', 'dependencies'].includes(op) ? { page: { size: 20, ...(cursor ? { cursor } : {}) } } : {}) }, signal);
    const scope = ['TYPE', 'MODULE', 'PACKAGE'].includes(item.kind) ? item.kind : item.kind === 'BEAN_DEFINITION_CANDIDATE' ? 'SPRING_COMPONENT' : ['METHOD', 'CONSTRUCTOR', 'FIELD', 'PARAMETER', 'RECORD_COMPONENT'].includes(item.kind) ? 'ENTITY' : null;
    const actions = scope ? [
      { label: 'Dependencies (Outgoing)', run: () => inspect(M.label(item), 'DEPENDENCIES', (signal, cursor) => query('dependencies', { identity: item.identity }, signal, { scope, relationshipKinds: relationshipKinds(scope) }, cursor)) },
      { label: 'Dependents (Incoming)', run: () => inspect(M.label(item), 'INCOMING DEPENDENCIES', (signal, cursor) => query('dependencies', { identity: item.identity }, signal, { scope, relationshipKinds: relationshipKinds(scope), direction: 'INCOMING' }, cursor)) },
      { label: 'Blast radius / Impact', run: () => inspect(M.label(item), 'POTENTIAL STRUCTURAL IMPACT', signal => query('impact', { identity: item.identity }, signal, { scope, relationshipKinds: relationshipKinds(scope), direction: 'INCOMING' })) },
      { label: 'Open in graph', run: async () => { state.graphFocus = { seed: item.identity, scope, context: env.context }; closeInspector(); await loadTab('graph'); } },
      { label: 'Metric details', run: async () => { state.metricSubject = item; closeInspector(); await loadTab('metrics'); } }
    ] : [];
    const sourcePath = item.path || item.properties?.path || (item.kind === 'SOURCE_DOCUMENT' ? (item.qualifiedName || item.spelling) : '');
    if (sourcePath && !String(sourcePath).startsWith('sha256:')) {
      actions.unshift({ label: 'View captured source', run: () => openCapturedSourceModal(env.graph, sourcePath) });
    }
    return inspect(M.label(item), item.kind || 'ENTITY', (signal, cursor) => query('explain', { identity: item.identity }, signal, {}, cursor), actions);
  }
  function downloadBlob(blob, name) { const url = URL.createObjectURL(blob), link = el('a'); link.href = url; link.download = name; document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 10000); }
  function download(value, name) { downloadBlob(new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }), name); }
  async function inspectFinding(item, reportId, env) {
    const finding = item.result, bundle = finding.bundle, rule = finding.rule?.header || finding.rule, actions = [];
    if (bundle) {
      if (item.bundleIdentity) {
        actions.push({ label: 'Diagnose finding', run: () => inspectDiagnosis(item, reportId, env) });
        if (state.capabilities?.architectureChatEnabled)
          actions.push({ label: 'Ask architecture chat', run: () => askAboutFinding(item, reportId, env) });
      }
      actions.push({
        label: 'Exact source evidence', run: async () => {
          const sourceContext = bundle.analysis && state.contexts.some(context => context.identity === bundle.analysis) ? bundle.analysis : env.context;
          await resolveLabels(bundle.sources.map(source => source.span.document), { ...env, query: (op, args, page, overrides) => env.query(op, args, page, { ...overrides, context: sourceContext }) });
          if (!env.signal.aborted) return inspect('Finding sources', 'SOURCE EVIDENCE', { items: bundle.sources, status: bundle.status });
        }
      });
      for (const [counter, label] of [[false, 'Replay finding witness'], [true, 'Replay counterexample']]) {
        if (!(counter ? bundle.region.counterWitness : bundle.region.witness)) continue;
        actions.push({
          label, run: async () => {
            await inspect(label, 'MODEL REPLAY', signal => api('/graphs/' + env.graph + '/reports/' + reportId + '/replay', { arguments: { identity: item.identity, counterWitness: counter } }, signal));
            if (!state.inspector?.signal.aborted && state.details?.items?.[0]?.actualTruth) {
              const value = state.details?.items?.[0];
              $('inspector-body').prepend(append(el('div', 'notice'), el('strong', '', value?.matched ? 'Witness matches the captured model' : value?.actualTruth === 'UNKNOWN' ? 'Replay could not establish the result' : 'Witness does not match'), el('p', 'small', 'Checks the stored rule predicate at its recorded configuration. This does not start Spring or execute repository code.')));
            }
          }
        });
      }
      actions.push({ label: 'Finding path', run: () => inspect('Finding path', 'DEPENDENCY EVIDENCE', { items: [{ vertices: bundle.path, edges: bundle.relationships, feasibility: bundle.region.classification === 'UNKNOWN' ? 'UNKNOWN' : 'SAT' }], status: bundle.status }) });
      if (bundle.path.length) actions.push({ label: 'Show finding graph', run: () => { state.graphFocus = { seed: bundle.path[0], context: bundle.request.context ?? env.context, scope: bundle.request.scope, relationshipKinds: bundle.request.relationshipKinds }; closeInspector(); return loadTab('graph'); } });
    }
    return inspect(rule?.name || rule?.id || 'Rule evidence', 'POLICY EVIDENCE', finding, actions);
  }
  const diagnosisReasons = Object.freeze({
    UNSUPPORTED_FINDING: 'Diagnosis requires verified dependency path and supported rule kinds (FORBIDDEN_DEPENDENCY, ACYCLIC, BOUNDARY).',
    GRAPH_PROOF_MISMATCH: 'The recorded graph evidence does not match the canonical dependency path.',
    MODEL_SCOPE_NOT_REGISTERED: 'Diagnosis model is running in deterministic local replay mode.',
    MODEL_UNAVAILABLE: 'The diagnosis provider did not return a result.',
    INVALID_MODEL_CITATIONS: 'The diagnosis provider returned citations that could not be verified for this finding.'
  });
  function inspectDiagnosis(item, reportId, env, previous) {
    const args = { identity: item.identity, bundle: item.bundleIdentity, context: env.context };
    const loader = previous ?? (signal => api('/graphs/' + env.graph + '/reports/' + reportId + '/diagnosis', { arguments: args }, signal));
    const back = { label: 'Back to finding', run: () => inspectFinding(item, reportId, env) };
    return inspect('Finding diagnosis', 'AI DIAGNOSIS', loader, [back], [], undefined, (body, diagnosis) => {
      const status = el('div', 'notice'); status.setAttribute('role', 'status');
      const local = diagnosis.mode === 'LOCAL_REPLAY';
      const validated = diagnosis.status === 'CITATIONS_VALIDATED';
      append(status, el('strong', '', (local ? 'Local replay' : 'Live model') + ' · ' + (validated ? 'Citations validated' : 'Diagnosis withheld')),
        el('p', 'small', local ? 'Uses an authored response. No live AI model was called.'
          : 'The local model selects citations. The explanation comes from checked graph evidence.'));
      body.append(status);
      if (!validated) {
        body.append(empty('Diagnosis unavailable', diagnosisReasons[diagnosis.reason] || ''));
        const f = item.result || {};
        if (f.kind === 'ACYCLIC' && f.bundle?.path) {
          const cycleCard = el('div', 'notice');
          cycleCard.style.marginTop = '12px';
          append(cycleCard,
            el('strong', '', 'Circular Dependency Structure'),
            el('p', 'small', `This finding represents a circular dependency cycle across ${f.bundle.path.length} classes: ${f.bundle.path.join(' → ')}.`),
            el('p', 'small', 'Resolving cycles requires multi-class architectural decoupling (such as extracting an interface or mediating service). Single-patch automated recipes apply to direct forbidden dependencies (e.g. Layering rules).'),
            btn('View cycle path in Graph ↗', () => {
              state.graphFocus = { seed: f.bundle.path[0], scope: f.bundle.request?.scope || 'TYPE' };
              closeInspector();
              return loadTab('graph');
            }, 'button subtle')
          );
          body.append(cycleCard);
        }
        return;
      }
      body.append(el('p', '', diagnosis.explanation), el('h3', '', 'Source citations'));
      const list = el('ol', 'dependency-path');
      for (const citation of diagnosis.citations) {
        const s = citation.span;
        const label = citation.path + ' · ' + s.startLine + ':' + s.startColumn + '–' + s.endLine + ':' + s.endColumn;
        const open = () => inspect('Cited source evidence', 'SOURCE EVIDENCE', async signal => {
          const evidence = await api('/graphs/' + env.graph + '/queries/evidence', {
            request: { ...M.request(citation.context), scope: 'ENTITY' }, arguments: { reference: citation.evidence }
          }, signal);
          const record = evidence.items.find(row => row.identity === citation.evidence && row.family === citation.family);
          if (!record) throw new Error('The cited evidence is no longer available in this snapshot.');
          return { citation, record };
        }, [{ label: 'Back to diagnosis', run: () => inspectDiagnosis(item, reportId, env, diagnosis) }], [], undefined, (detail, data) => {
          detail.append(el('p', 'source-coord-pill diagnosis-coordinate', label));
          renderFields(detail, data, 0);
        });
        list.append(append(el('li'), btn(label, open, 'text-button')));
      }
      body.append(list);
      body.append(btn('Create captured repair proposal', () => inspectCapturedRepair(item, reportId, env, diagnosis), 'button primary'));
      body.append(btn('Review repair proposal', () => inspectRepairProposal(item, reportId, env, diagnosis, args), 'button primary'));
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
  function inspectCapturedRepair(item, reportId, env, diagnosis) {
    const args = { identity: item.identity, bundle: item.bundleIdentity, context: env.context, diagnosisInput: diagnosis.input };
    const options = signal => api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-options', { arguments: args }, signal);
    const back = { label: 'Back to diagnosis', run: () => inspectDiagnosis(item, reportId, env, diagnosis) };
    return inspect('Captured repair options', 'PROPOSED CHANGE', options, [back], [], undefined, (body, value) => {
      const workspace = el('section', 'surface-section');
      body.append(workspace);
      renderProposalResult(item, reportId, { ...env, signal: state.inspector.signal }, diagnosis, value, null, workspace);
    });
  }
  function inspectRepairProposal(item, reportId, env, diagnosis, diagnosisArguments) {
    const request = { arguments: { ...diagnosisArguments, diagnosisInput: diagnosis.input } };
    const load = signal => api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-proposal', request, signal);
    const back = { label: 'Back to diagnosis', run: () => inspectDiagnosis(item, reportId, env, diagnosis) };
    return inspect('Repair proposal', 'PROPOSED CHANGE', load, [back], [], undefined, (body, outcome) => {
      if (outcome.status !== 'PROPOSED') {
        const message = empty('Proposal withheld', repairReasons[outcome.reason] || 'The source change could not be proposed.');
        message.setAttribute('role', 'status'); body.append(message);
        return;
      }
      const { plan, affectedScope, edit, engine, patch } = outcome.proposal.candidate;
      const status = el('div', 'notice'); status.setAttribute('role', 'status');
      append(status, el('strong', '', 'Unverified repair proposal'), el('p', 'small', 'OpenRewrite ' + engine.version));
      if (diagnosis.mode === 'LIVE') status.append(el('p', 'small', 'The recipe rechecks the same graph and source evidence independently.'));
      body.append(status, el('p', '', plan.summary),
        el('p', 'muted small', affectedScope.rule.id + ' · ' + affectedScope.classification + ' · ' + affectedScope.relationships.join(', ')));
      const steps = el('ol', 'dependency-path');
      for (const step of plan.steps) steps.append(el('li', '', step));
      body.append(steps, el('h3', '', edit.path));
      const diff = el('pre', 'repair-patch', patch);
      diff.setAttribute('aria-label', 'Unified patch for ' + edit.path);
      body.append(diff, btn('Download patch', () => downloadBlob(new Blob([patch], { type: 'text/x-diff' }), 'repair.patch')));
      body.append(btn('View verification', () => inspectRepairVerification(item, reportId, env, diagnosis, diagnosisArguments, outcome), 'button primary'));
      const checks = el('ul', 'dependency-path');
      for (const check of plan.verificationChecks) checks.append(el('li', '', check));
      body.append(el('h3', '', 'Planned verification'), checks);
    });
  }
  const repairCheckNames = {
    'fixture-inputs': 'Baseline and patch', 'candidate-finding': 'Candidate finding',
    'mutation-control': 'Negative control', 'isolated-runtime': 'Isolated build and Spring checks'
  };
  function inspectRepairVerification(item, reportId, env, diagnosis, diagnosisArguments, outcome, operands = {}) {
    const proposal = outcome.proposal;
    const argumentsForCheck = { ...diagnosisArguments, diagnosisInput: diagnosis.input, proposal: proposal.identity, ...operands };
    const load = signal => api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-verification', { arguments: argumentsForCheck }, signal);
    const back = { label: 'Back to proposal', run: () => inspectRepairProposal(item, reportId, env, diagnosis, diagnosisArguments) };
    return inspect('Local PR preview', 'REPAIR VERIFICATION', load, [back], [], undefined, (body, result) => {
      const status = el('div', 'notice'); status.setAttribute('role', 'status');
      const verdict = { VERIFIED: 'Verified repair', VERIFIED_DEMO: 'Verified in the demo VM', UNVERIFIED: 'Unverified repair', REJECTED: 'Repair rejected' };
      append(status, el('strong', '', verdict[result.verdict]), el('p', 'small', proposal.candidate.plan.summary));
      body.append(status, el('h3', '', 'Verification checks'));
      const list = el('ul', 'dependency-path');
      for (const check of result.checks) {
        const label = repairCheckNames[check.id] || check.id;
        const states = { PASSED: 'Passed', FAILED: 'Failed', NOT_RUN: 'Not run', INCONCLUSIVE: 'Inconclusive' };
        const explanation = {
          REGISTERED_WORKER_EVIDENCE_REQUIRED: 'A protected worker result is required.',
          PROTECTED_RECEIPT_MISSING: 'The worker receipt is missing.',
          REANALYSIS_REPORT_REQUIRED: 'Add the published report for this snapshot.',
          REANALYSIS_SNAPSHOT_MISMATCH: 'This report belongs to different source bytes.',
          FINDING_RESOLVED_IN_DEMO_SCOPE: 'The source census and strict comparison prove this finding is resolved for the demo profile scope.',
          FINDING_TRANSITION_UNPROVED: 'The comparison could not prove a finding transition.'
        }[check.reason];
        list.append(append(el('li'), el('strong', '', label + ' · ' + states[check.status]), explanation && el('div', 'muted small', explanation)));
      }
      body.append(list);
      if (result.demoProof) {
        body.append(el('h3', '', 'Spring dependency by profile'), el('p', 'muted small', 'The demo VM checked the sealed fixture with dev off and on. The canonical snapshot comparison keeps its own verdict.'));
        const table = el('table', 'comparison-table');
        const heading = append(el('tr'), ...['Profile', 'Baseline', 'Candidate', 'Negative control'].map(label => el('th', '', label)));
        for (const cell of heading.children) cell.scope = 'col';
        table.append(append(el('thead'), heading));
        const rows = el('tbody');
        for (const cell of result.demoProof.cells) {
          const label = el('th', '', cell.dev ? 'dev on' : 'dev off'); label.scope = 'row';
          const row = append(el('tr'), label);
          for (const value of [cell.baseline, cell.candidate, cell.control]) row.append(el('td', '', value ? 'Present' : 'Absent'));
          rows.append(row);
        }
        body.append(append(el('div', 'table-scroll'), append(table, rows)));
      }
      const form = el('form');
      const fields = ['candidateReport', 'controlReport'].map((name, index) => {
        const input = el('input'); input.name = name; input.value = operands[name] || '';
        input.pattern = 'sha256:[0-9a-f]{64}'; input.maxLength = 71; input.spellcheck = false;
        form.append(append(el('label'), el('p', 'small', index === 0 ? 'Candidate report digest' : 'Negative control report digest'), input));
        return input;
      });
      const submit = el('button', 'button', 'Check published reports'); submit.type = 'submit'; form.append(submit);
      form.addEventListener('submit', event => {
        event.preventDefault();
        const reports = Object.fromEntries(fields.filter(input => input.value).map(input => [input.name, input.value]));
        return safe(() => inspectRepairVerification(item, reportId, env, diagnosis, diagnosisArguments, outcome, reports));
      });
      body.append(append(el('details'), el('summary', '', 'Attach published comparison inputs'), form),
        el('h3', '', proposal.candidate.edit.path), el('pre', 'repair-patch', result.patch));
    });
  }
  async function inspectScoreDimension(assessment, dimension, reportId, env) {
    const needsFindings = dimension.contributions.some(contribution => contribution.unit === 'potential-root');
    const page = needsFindings ? await env.report(reportId, 'findings', {}, { size: 50 }) : null;
    if (env.signal.aborted) return;
    const evidence = M.scoreEvidence(assessment, dimension, page?.items);
    const actions = evidence.findings.map(item => ({
      label: `Open ${item.result.rule.id} finding (${M.shortDigest(item.identity)})`,
      run: () => inspectFinding(item, reportId, env)
    }));
    if (evidence.metric) actions.push({ label: 'Inspect metric operand', run: () => inspect('Score metric operand', 'CANONICAL METRIC', evidence.metric) });
    return inspect('Score dimension', 'ASSESSMENT EVIDENCE', dimension, actions);
  }
  function layoutConfig(name) {
    if (name === 'concentric') return { name: 'concentric', padding: 36, animate: false, minNodeSpacing: 28, concentric: node => node.degree(), levelWidth: () => 2 };
    if (name === 'circle') return { name: 'circle', padding: 36, animate: false, spacingFactor: 1.2 };
    if (name === 'grid') return { name: 'grid', padding: 36, animate: false, condense: true, spacingFactor: 1.25 };
    return { name: 'breadthfirst', directed: true, padding: 35, spacingFactor: 1.4, animate: false };
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
            state.graphFocus = { seed: v, scope: bundle.request?.scope || 'TYPE' };
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
          const replay = await api('/graphs/' + env.graph + '/reports/' + reportId + '/replay', { arguments: { identity: item.identity, counterWitness: false } });
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
          state.graphFocus = { seed: bundle.path[0], scope: bundle.request?.scope || 'TYPE' };
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
          state.graphFocus = { seed: bundle.path[0], scope: bundle.request?.scope || 'TYPE' };
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
      const args = { identity: item.identity, bundle: item.bundleIdentity, context: env.context };
      const diagnosis = await api('/graphs/' + env.graph + '/reports/' + reportId + '/diagnosis', { arguments: args });

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
        el('p', '', validated ? diagnosis.explanation : diagnosisReasons[diagnosis.reason] || 'Diagnosis unavailable.')
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
                request: { ...M.request(cit.context), scope: 'ENTITY' }, arguments: { reference: cit.evidence }
              }, signal);
              const record = evidence.items.find(r => r.identity === cit.evidence && r.family === cit.family);
              return { citation: cit, record };
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

    const rankedRecipes = M.rankApplicableRecipes
      ? M.rankApplicableRecipes(options.recipes || [], options.applicableRecipes || [])
      : (options.recipes || []).map(r => ({
          ...r,
          applicable: (options.applicableRecipes || []).includes(r.id),
          displaySummary: r.summary || r.title || r.id
        }));

    const controls = el('div', 'remedy-patch-actions');
    const recipeSelect = document.createElement('select');
    recipeSelect.className = 'input';
    recipeSelect.setAttribute('aria-label', 'Repair recipe');
    rankedRecipes.forEach(r => {
      const opt = document.createElement('option');
      opt.value = r.id;
      opt.textContent = (r.applicable ? '★ [Applicable] ' : '') + (r.title || r.id);
      opt.title = r.displaySummary;
      recipeSelect.append(opt);
    });
    const firstApplicable = rankedRecipes.find(r => r.applicable);
    if (firstApplicable) recipeSelect.value = firstApplicable.id;

    const recipeSummaryNote = el('p', 'small muted', '');
    const syncRecipeSummary = () => {
      const chosen = rankedRecipes.find(r => r.id === recipeSelect.value);
      recipeSummaryNote.textContent = chosen
        ? (chosen.applicable ? 'AST pre-check matched cited files · ' : '') + chosen.displaySummary
        : 'No registered recipes available.';
    };
    recipeSelect.addEventListener('change', syncRecipeSummary);
    syncRecipeSummary();

    const runRecipeBtn = btn('Run Recipe', () => executeProposalChoice(item, reportId, env, diagnosis, options, {
      strategy: 'RECIPE',
      recipe: recipeSelect.value
    }, propBody), 'button primary');
    runRecipeBtn.disabled = !rankedRecipes.length;
    controls.append(recipeSelect, runRecipeBtn);
    propBody.append(controls, recipeSummaryNote);

    const applicableList = rankedRecipes.filter(r => r.applicable);
    if (applicableList.length) {
      const recipeGrid = el('div', 'repair-recipe-grid');
      for (const r of applicableList) {
        const cardNode = el('div', 'repair-recipe-card is-applicable');
        const info = append(el('div'),
          append(el('div', 'remedy-patch-header'),
            el('strong', '', r.title || r.id),
            el('span', 'badge good', 'Applicable AST match')),
          el('p', 'repair-recipe-summary', r.displaySummary));
        const applyBtn = btn('Apply', () => {
          recipeSelect.value = r.id;
          syncRecipeSummary();
          return executeProposalChoice(item, reportId, env, diagnosis, options, { strategy: 'RECIPE', recipe: r.id }, propBody);
        }, 'button subtle');
        cardNode.append(info, applyBtn);
        recipeGrid.append(cardNode);
      }
      propBody.append(recipeGrid);
    }

    if (options.ai?.input) {
      const disclosure = el('div', 'notice');
      const provider = options.ai.provider;
      const approval = el('input');
      approval.type = 'checkbox';
      const files = el('ul', 'dependency-path');
      for (const file of options.ai.files || []) files.append(el('li', '', file.path));
      const aiBtn = btn(`Propose with AI (${options.ai.provider?.model || 'configured'})`, () => executeProposalChoice(item, reportId, env, diagnosis, options, {
        strategy: 'AI',
        modelInput: options.ai.input,
        ...(state.capabilities?.architectureAgentEnabled ? { maxRepairAttempts: 3 } : {})
      }, propBody), 'button primary');
      aiBtn.disabled = true;
      approval.addEventListener('change', () => { aiBtn.disabled = !approval.checked; });
      append(disclosure, el('p', '', 'This proposal sends the captured source of the listed files to ' + provider.endpoint + '.'),
        files, append(el('label', 'remedy-verify-toggle'), approval, el('span', '', 'Allow this source disclosure')), aiBtn);
      propBody.append(disclosure);
    }

    if (!proposal) return;
    if (proposal.status === 'PROPOSED') {
      const cand = proposal.proposal?.candidate || {};
      const patch = cand.patch || '';
      const targetList = cand.edits?.length ? cand.edits.map(e => e.path).join(', ') : (cand.edit?.path || 'source');
      const attempts = Number(proposal.attempts ?? cand.attempts ?? 1);
      const patchLines = patch.split('\n');
      let addCount = 0, delCount = 0;
      for (const l of patchLines) {
        if (l.startsWith('+') && !l.startsWith('+++')) addCount++;
        else if (l.startsWith('-') && !l.startsWith('---')) delCount++;
      }

      const infoRow = el('div', 'remedy-patch-header');
      append(infoRow,
        el('span', 'badge good', 'Compiler Syntax PASSED'),
        attempts > 1 ? el('span', 'badge good', `AI Self-Healed · ${attempts} attempts`) : null,
        el('span', 'badge warn', 'Unverified candidate'),
        el('span', 'diff-stat-pill is-add', `+${addCount}`),
        el('span', 'diff-stat-pill is-del', `-${delCount}`),
        el('span', 'small muted', `Target: ${targetList} · Strategy: ${cand.strategy || cand.engine?.version || 'RECIPE'}`)
      );
      propBody.append(infoRow);

      if (cand.recipe?.summary || cand.recipe?.title || cand.plan?.summary) {
        propBody.append(el('p', 'small', cand.recipe?.summary || cand.recipe?.title || cand.plan.summary));
      }

      const diffBox = el('div', 'diff-viewer');
      const diffPre = el('pre', 'diff-code');
      let oldLine = 0, newLine = 0;
      patchLines.forEach(line => {
        const lineEl = el('div', 'diff-line');
        const hunk = line.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
        if (hunk) {
          oldLine = Number(hunk[1]);
          newLine = Number(hunk[2]);
          lineEl.className += ' diff-chunk';
          lineEl.textContent = line;
        } else if (line.startsWith('+++') || line.startsWith('---')) {
          lineEl.textContent = line;
        } else if (line.startsWith('+')) {
          lineEl.className += ' diff-add';
          lineEl.append(el('span', 'diff-line-num', String(newLine++)), document.createTextNode(line));
        } else if (line.startsWith('-')) {
          lineEl.className += ' diff-del';
          lineEl.append(el('span', 'diff-line-num', String(oldLine++)), document.createTextNode(line));
        } else {
          if (oldLine || newLine) lineEl.append(el('span', 'diff-line-num', String(newLine++ || oldLine++)));
          lineEl.append(document.createTextNode(line));
          if (oldLine) oldLine++;
        }
        diffPre.append(lineEl);
      });
      diffBox.append(diffPre);
      propBody.append(diffBox);

      const actionRow = el('div', 'remedy-patch-actions');
      const copyBtn = btn('Copy Patch', async () => {
        try {
          await navigator.clipboard.writeText(patch);
          copyBtn.textContent = 'Copied!';
          window.EvoToast?.push({ kind: 'ok', title: 'Patch copied', detail: targetList });
          setTimeout(() => { copyBtn.textContent = 'Copy Patch'; }, 1500);
        } catch (_) { }
      }, 'button subtle');

      const dlBtn = btn('Download .patch', () => {
        downloadBlob(new Blob([patch], { type: 'text/x-diff' }), 'evolution-repair.patch');
      }, 'button subtle');

      const dlProposalBtn = btn('Download proposal.json', () => {
        downloadBlob(new Blob([JSON.stringify(proposal, null, 2)], { type: 'application/json' }), 'repair-proposal.json');
      }, 'button subtle');

      append(actionRow, copyBtn, dlBtn, dlProposalBtn);
      propBody.append(actionRow);

      // Inline Verification Workbench (never depends on missing global DOM IDs!)
      const propId = proposal?.proposal?.identity;
      if (propId) {
        const verifyPanel = el('section', 'remedy-verify-panel');
        const verifyHead = append(el('div', 'remedy-patch-header'),
          el('strong', '', 'Candidate Verification & Reanalysis'));
        const verdictBadge = el('span', 'badge warn', 'Not verified yet');
        verifyHead.append(verdictBadge);

        const isolatedConfigured = Boolean(options.isolatedRunnerConfigured || state.capabilities?.isolatedRepairRunnerEnabled);
        const isolatedToggle = el('input');
        isolatedToggle.type = 'checkbox';
        isolatedToggle.name = 'runIsolated';
        isolatedToggle.disabled = !isolatedConfigured;
        const isolatedLabel = append(el('label', 'remedy-verify-toggle'), isolatedToggle,
          el('span', '', isolatedConfigured
            ? 'Run isolated QEMU/Docker build & test verification (runIsolated)'
            : 'Isolated build runner not configured on this server'));

        const verifyOut = el('div');
        let currentOperands = {};

        async function runInlineVerification(extraOperands = {}) {
          currentOperands = { ...currentOperands, ...extraOperands };
          verifyBtn.disabled = true;
          if (reanalyzeBtn) reanalyzeBtn.disabled = true;
          verifyOut.replaceChildren(skeleton(2), el('p', 'small muted', isolatedToggle.checked
            ? 'Running server verification + isolated build worker…'
            : 'Checking baseline pins, reanalysis comparisons, and isolated proof…'));
          try {
            const verifyArgs = {
              identity: item.identity,
              bundle: item.bundleIdentity,
              context: env.context,
              diagnosisInput: diagnosis.input,
              proposal: propId,
              ...(currentOperands.candidateReport ? { candidateReport: currentOperands.candidateReport } : {}),
              ...(currentOperands.controlReport ? { controlReport: currentOperands.controlReport } : {}),
              ...(isolatedToggle.checked ? { runIsolated: true } : {})
            };
            const outcome = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-verification', { arguments: verifyArgs }, env.signal);
            const verified = outcome.verdict === 'VERIFIED' || outcome.verdict === 'VERIFIED_DEMO';
            const rejected = outcome.verdict === 'REJECTED';
            verdictBadge.textContent = outcome.verdict || 'UNVERIFIED';
            verdictBadge.className = 'badge ' + (verified ? 'good' : (rejected ? 'bad' : 'warn'));
            verifyOut.replaceChildren();

            const checkCard = el('div', 'remedy-verif-card');
            checkCard.append(el('strong', '', 'Verification Checks'));
            const list = el('ul', 'remedy-check-list');
            for (const c of outcome.checks || []) {
              const passed = c.status === 'PASSED', failed = c.status === 'FAILED';
              const li = el('li', 'check-item ' + (passed ? 'is-pass' : failed ? 'is-fail' : 'is-warn'));
              append(li,
                el('span', 'check-icon', passed ? '✔' : failed ? '✘' : '•'),
                append(el('div'),
                  el('strong', '', `${c.id || c.name} (${c.status})`),
                  el('p', 'small muted', (c.reason || '') + (c.evidence ? ` · ${M.shortDigest(c.evidence)}` : ''))));
              list.append(li);
            }
            checkCard.append(list);
            verifyOut.append(checkCard);

            if (outcome.candidateComparison) {
              const cc = outcome.candidateComparison;
              const cmpCard = el('div', 'remedy-verif-card');
              append(cmpCard,
                el('strong', '', 'Candidate Snapshot Comparison'),
                el('p', 'small', `Status: ${cc.status} · Transitions: ${(cc.transitions || []).join(', ') || 'none'} · Regression: ${cc.regressionDetected ? 'YES' : 'NO'}`),
                el('p', 'small muted', `Reasons: ${(cc.reasons || []).join(', ') || 'none'}`));
              verifyOut.append(cmpCard);
            }
            if (outcome.isolatedProof) {
              const vm = outcome.isolatedProof;
              const vmCard = el('div', 'remedy-verif-card');
              append(vmCard,
                el('strong', '', 'Isolated Worker Build & Test Proof'),
                el('p', 'small', `Tool: ${vm.buildTool} · Modules: ${(vm.modules || []).join(', ')} · Compile exit: ${vm.compileExit} · Test exit: ${vm.testExit}`),
                el('p', 'small', `Tests run: ${vm.testsRun}, failed: ${vm.testsFailed}, errors: ${vm.testsErrors} · Bytecode classes: ${vm.classCount}`),
                el('p', 'small mono muted', `Capture: ${M.shortDigest(vm.capture)} · Census: ${M.shortDigest(vm.classCensusDigest)}`));
              verifyOut.append(vmCard);
            }
            const receipt = el('div', 'remedy-pr-box' + (verified ? '' : rejected ? ' is-rejected' : ' is-unverified'));
            append(receipt,
              el('strong', '', verified ? 'Verified Patch Receipt' : rejected ? 'Verification Rejected' : 'Verification Incomplete'),
              el('p', 'small mono', `Plan: ${M.shortDigest(outcome.planIdentity)} · Proposal: ${M.shortDigest(propId)}`),
              el('p', 'small', verified
                ? 'Server-verified: all required checks passed and original policy finding is resolved.'
                : rejected
                  ? 'One or more verification checks failed. Inspect failed checks above before using this patch.'
                  : 'Run candidate reanalysis and/or isolated build verification to establish a complete proof.'));
            verifyOut.append(receipt);
            window.EvoToast?.push({
              kind: verified ? 'ok' : rejected ? 'err' : 'warn',
              title: 'Verification: ' + (outcome.verdict || 'UNVERIFIED'),
              detail: targetList
            });
          } catch (err) {
            if (err.name !== 'AbortError') {
              verdictBadge.textContent = 'UNVERIFIED';
              verdictBadge.className = 'badge warn';
              verifyOut.replaceChildren(empty('Verification unavailable', err.message));
            }
          } finally {
            verifyBtn.disabled = false;
            if (reanalyzeBtn) reanalyzeBtn.disabled = false;
          }
        }

        const verifyBtn = btn('Verify Candidate →', () => runInlineVerification(), 'button primary');
        const reanalyzeBtn = state.capabilities?.repairReanalysisEnabled
          ? btn('Run Candidate Reanalysis + Control', async () => {
              reanalyzeBtn.disabled = true;
              verifyBtn.disabled = true;
              verifyOut.replaceChildren(skeleton(2), el('p', 'small muted', 'Reanalyzing candidate + negative control in parallel…'));
              try {
                const reResult = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-reanalysis', {
                  arguments: {
                    identity: item.identity, bundle: item.bundleIdentity, context: env.context,
                    diagnosisInput: diagnosis.input, proposal: propId, includeControl: true
                  }
                }, env.signal);
                await runInlineVerification({
                  candidateReport: reResult.candidateReport || currentOperands.candidateReport,
                  controlReport: reResult.controlReport || currentOperands.controlReport
                });
              } catch (err) {
                if (err.name !== 'AbortError') verifyOut.replaceChildren(empty('Reanalysis error', err.message));
                reanalyzeBtn.disabled = false;
                verifyBtn.disabled = false;
              }
            }, 'button subtle')
          : null;

        const verifyActions = append(el('div', 'remedy-patch-actions'), verifyBtn, reanalyzeBtn);
        verifyPanel.append(verifyHead, isolatedLabel, verifyActions, verifyOut);
        propBody.append(verifyPanel);
      }

      const topDlBtn = $('remedy-download-btn');
      if (topDlBtn) topDlBtn.onclick = () => downloadBlob(new Blob([patch], { type: 'text/x-diff' }), 'evolution-repair.patch');
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
        el('p', 'small', repairReasons[proposal.reason] || proposal.reason || 'Select an applicable recipe above or configure an AI repair model for this finding.'),
        el('p', 'small muted', 'Applicable recipes: ' + (applicableList.map(r => r.id).join(', ') || 'none')
          + ' · Registered total: ' + rankedRecipes.length)
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
        { identity: item.identity, bundle: item.bundleIdentity, context: env.context, diagnosisInput: diagnosis.input },
        extraArgs
      );
      const proposal = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-proposal', { arguments: args }, env.signal);
      renderProposalResult(item, reportId, env, diagnosis, options, proposal, propBody);
    } catch (e) {
      if (e.name !== 'AbortError') propBody.replaceChildren(empty('Proposal unavailable', e.message),
        btn('Back to repair options', () => renderProposalResult(item, reportId, env, diagnosis, options, null, propBody)));
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
      const baseArgs = { identity: item.identity, bundle: item.bundleIdentity, context: env.context, diagnosisInput: diagnosis.input };
      const options = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-options', { arguments: baseArgs });
      let proposal = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-proposal', { arguments: baseArgs });

      if (proposal.status !== 'PROPOSED' && options.recipes?.length) {
        for (const r of options.recipes) {
          const candidate = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-proposal', {
            arguments: Object.assign({}, baseArgs, { strategy: 'RECIPE', recipe: r.id })
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

      const outcome = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-verification', { arguments: args });
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
            const reResult = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-reanalysis', { arguments: Object.assign({}, args, { includeControl: true }) });
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

  function savedScorePaths(setup) {
    try { return JSON.parse(localStorage.getItem('evo.rubric.' + setup)) || {}; }
    catch (_) { return {}; }
  }

  function requestRepositoryScore(job, graph, paths, signal) {
    const body = Object.keys(paths).length ? { paths } : undefined;
    const fromGraph = () => api('/graphs/' + graph + '/repository-score', body, signal);
    return job ? api('/analysis/jobs/' + job + '/repository-score', body, signal).catch(error => {
      if (!graph || error.code !== 'SCORE_INVENTORY_UNAVAILABLE') throw error;
      return fromGraph();
    }) : fromGraph();
  }

  function repositoryScoreCard(env, editable = false) {
    const node = card('Repository score', 'File-based checks for this snapshot.');
    if (!editable) node.classList.add('overview-score');
    const result = el('div');
    result.setAttribute('aria-live', 'polite');
    node.append(result);
    const fields = Object.entries({
      documentation: 'Documentation', tests: 'Tests', automation: 'CI configuration', build: 'Build or package setup'
    });
    const key = 'evo.rubric.' + env.setup;
    let paths = savedScorePaths(env.setup);
    let version = 0;
    const feedback = el('p', 'small muted');
    feedback.setAttribute('role', 'status');
    let inputs;
    if (editable) {
      const editor = el('details', 'snapshot-policy-editor');
      editor.append(el('summary', '', 'Customize repository checks'),
        el('p', 'small muted', 'Enter an exact path inside the repository to replace a default check. Leave a path empty to use the convention.'));
      inputs = fields.map(([id, title]) => {
        const input = el('input');
        input.value = paths[id] ?? '';
        input.maxLength = 1024;
        input.placeholder = 'Use default convention';
        editor.append(field(title, input));
        return [id, input];
      });
      editor.append(append(el('div', 'toolbar'), btn('Apply checks', () => read(
        Object.fromEntries(inputs.map(([id, control]) => [id, control.value.trim()]).filter(([, value]) => value)), true)),
        btn('Use default checks', () => read({}, true))), feedback);
      node.append(editor);
    }
    async function read(requested = paths, save = false) {
      const current = ++version, publication = state.publication;
      if (!save) result.textContent = 'Reading repository score…';
      else feedback.textContent = 'Evaluating checks…';
      const cacheKey = JSON.stringify(requested);
      if (state.repositoryScore?.key !== cacheKey) {
        const promise = requestRepositoryScore(env.jobId, env.graph, requested, publication.signal);
        const entry = { key: cacheKey, promise };
        state.repositoryScore = entry;
        promise.catch(() => { if (state.repositoryScore === entry) state.repositoryScore = null; });
      }
      try {
        const score = await state.repositoryScore.promise;
        if (env.signal.aborted || current !== version) return;
        paths = score.paths ?? {};
        if (save) {
          for (const [id, control] of inputs) control.value = paths[id] ?? '';
          try {
            localStorage.setItem(key, JSON.stringify(paths));
            feedback.textContent = 'Checks saved for this repository.';
          } catch (_) {
            feedback.textContent = 'Checks applied for this snapshot.';
          }
        }
        const value = score.score == null
          ? score.status === 'WITHHELD' || score.lower == null || score.upper == null ? 'Unavailable' : (score.lower === score.upper ? score.lower : score.lower + '–' + score.upper) + ' / 100'
          : score.score + ' / 100';
        const numericRing = score.score ?? (score.lower != null && score.upper != null ? (score.lower + score.upper) / 2 : null);
        result.replaceChildren(append(el('div', 'score-heading'),
          scoreRingSvg(numericRing, numericRing == null ? '—' : String(Math.round(numericRing))),
          el('strong', 'score', value)));
        const rows = el('dl', 'score-checks');
        for (const check of score.checks) {
          const label = fields.find(([id]) => id === check.name)?.[1] ?? check.name;
          const stateText = { OBSERVED: 'Observed', ABSENT: 'No match', UNKNOWN: '—', NOT_APPLICABLE: 'Not applicable' };
          const contribution = check.state === 'OBSERVED' ? check.weight + ' / ' + check.weight
            : check.state === 'ABSENT' ? '0 / ' + check.weight : stateText[check.state];
          const evidence = el('dd', 'small muted', check.criterion);
          if (check.evidence) evidence.append(' · ', btn(check.evidence.path, () => inspect('Scoring evidence', 'CAPTURED FILE',
            { snapshot: score.snapshot, check: check.criterion, path: check.evidence.path, evidence: check.evidence.identity }), 'text-button'));
          rows.append(el('dt', '', label), el('dd', 'score-points', contribution), evidence);
        }
        if (editable) result.append(rows);
        else result.append(append(el('details'), el('summary', '', 'How this score was calculated'), rows));
      } catch (error) {
        if (env.signal.aborted || current !== version) return;
        if (save) feedback.textContent = error.code === 'INVALID_REQUEST' ? 'Use a path inside the repository, such as qa/smoke.sh.' : error.message;
        else result.replaceChildren(el('p', '', error.message), btn('Retry score', () => read()));
      }
    }
    read();
    return node;
  }

  function architectureScoreCard(env) {
    const node = card('Architecture assessment', 'Rules are evaluated on the selected Java or Spring context of this snapshot.');
    if (!['analysis.manifest', 'spring.context'].includes(env.contextFamily) && !env.reportIds.length) {
      node.append(el('p', 'small muted', 'Select a Java or Spring analysis context to review architecture rules.'));
      return node;
    }
    const applicable = ['analysis.manifest', 'spring.context'].includes(env.contextFamily);
    const detail = el('div');
    detail.setAttribute('aria-live', 'polite');
    node.append(detail);
    let version = 0;
    function render(id, value, label) {
      const ringNum = value.score ?? value.overall ?? (value.lower != null && value.upper != null ? (value.lower + value.upper) / 2 : null);
      detail.replaceChildren(append(el('div', 'score-heading'),
        ringNum != null ? scoreRingSvg(ringNum, String(Math.round(ringNum))) : null,
        el('strong', 'score', M.assessment(value))));
      if (label) detail.append(el('p', 'small muted', label));
      for (const dimension of value.dimensions ?? []) {
        detail.append(append(el('div', 'dimension'), btn(dimension.id.replace(/^health[.:]/, '').replaceAll('-', ' '),
          () => inspectScoreDimension(value, dimension, id, env), 'text-button'), el('strong', '', dimension.score ?? 'Unavailable')));
      }
      detail.append(append(el('div', 'toolbar'),
        btn('View findings', () => { state.activePolicyReports.set(env.context, id); return loadTab('policy'); }),
        btn('Assessment evidence', () => inspect('Architecture assessment', 'ASSESSMENT', value), 'text-button')));
    }
    const preferenceKey = 'evo.rules.' + env.setup + '.' + (env.springContext ? 'spring' : 'java');
    let saved = '';
    try { saved = localStorage.getItem(preferenceKey) || ''; } catch (_) { }
    let editor, input, editorStatus, apply, defaults;
    const available = env.reportIds.filter(report => report.hasAssessment);
    if (available.length) {
      const select = selectControl('Published assessment', available.map((r, i) => [r.identity, 'Published assessment ' + (i + 1)]));
      const active = state.activePolicyReports.get(env.context);
      if (available.some(r => r.identity === active)) select.value = active;
      node.insertBefore(field('Report', select), detail);
      select.addEventListener('change', () => showPublished(select.value));
      if (!saved || !applicable) showPublished(select.value);
    }
    if (!applicable) return node;
    editor = el('details', 'snapshot-policy-editor');
    editor.id = 'snapshot-policy-editor';
    input = el('textarea');
    input.rows = 10;
    input.maxLength = 8192;
    input.value = saved;
    const templates = (M.policyTemplates ?? []).filter(item => env.springContext ? item.id.startsWith('spring-') : !item.id.startsWith('spring-'));
    const starter = templates.find(item => item.id === (env.springContext ? 'spring-cycles' : 'cycles'));
    if (!input.value) input.value = starter?.yaml ?? '';
    const preset = selectControl('Rule template', [['', 'Choose a template'], ...templates.map(item => [item.id, item.label])]);
    preset.addEventListener('change', () => {
      const selected = templates.find(item => item.id === preset.value);
      if (selected) input.value = selected.yaml;
      preset.value = '';
      input.focus();
    });
    editorStatus = el('p', 'small muted');
    editorStatus.setAttribute('role', 'status');
    apply = btn('Apply rules', () => run(input.value.trim(), true), 'button primary');
    defaults = btn('Use default rules', () => run(null, true));
    const autoDetect = state.capabilities?.suggestedPolicyEnabled
      ? btn('Auto-detect rules', async () => {
        autoDetect.disabled = true;
        editorStatus.textContent = 'Detecting package layers from graph…';
        try {
          const suggested = await api('/graphs/' + env.graph + '/suggested-policy', { context: env.context }, env.signal);
          if (suggested?.yaml) {
            input.value = suggested.yaml;
            editor.open = true;
            const layers = (suggested.detectedLayers || []).join(' -> ');
            editorStatus.textContent = layers
              ? `Synthesized rules for layers: ${layers}. Click Apply rules to evaluate.`
              : 'Synthesized starter policy from snapshot structure.';
            input.focus();
          }
        } catch (err) {
          if (!env.signal.aborted) editorStatus.textContent = err.message;
        } finally {
          autoDetect.disabled = false;
        }
      }, 'button auto-policy-button')
      : null;
    editor.append(el('summary', '', 'Customize rules'), field('Template', preset), field('Policy YAML', input),
      append(el('div', 'toolbar'), apply, autoDetect, defaults), editorStatus);
    node.append(editor);
    if (saved || !available.length) run(saved || null, false);
    return node;

    async function showPublished(id) {
      const current = ++version;
      if (apply) apply.disabled = defaults.disabled = false;
      detail.textContent = 'Reading published assessment…';
      try {
        const value = await env.report(id, 'assessment');
        if (!env.signal.aborted && current === version) render(id, value, 'Published policy');
      } catch (error) {
        if (!env.signal.aborted && current === version) detail.textContent = error.message;
      }
    }

    async function run(policy, save) {
      if (policy === '') { editorStatus.textContent = 'Enter policy YAML or choose a template.'; return; }
      const current = ++version, publication = state.publication;
      const runs = state.policyRuns, key = JSON.stringify([env.context, policy]);
      editorStatus.textContent = '';
      apply.disabled = defaults.disabled = true;
      if (!save) detail.textContent = 'Evaluating architecture rules…';
      try {
        let pending = runs.get(key);
        if (!pending) {
          pending = api('/graphs/' + env.graph + '/policy-assessment',
            { context: env.context, ...(policy == null ? {} : { policy }) }, publication.signal);
          runs.set(key, pending);
          pending.catch(() => runs.delete(key));
        }
        const response = await pending;
        if (env.signal.aborted || current !== version) return;
        if (!response.report) {
          editor.open = true;
          editorStatus.textContent = response.problems.map(problem =>
            (problem.location ? 'Line ' + problem.location.line + ' · ' : '') + problem.code.replaceAll('_', ' ')
            + ' (' + problem.path + ')').join(' · ');
          if (!save) detail.textContent = 'Correct the saved rules to issue an assessment.';
          return;
        }
        if (policy == null) input.value = response.policy;
        if (save) {
          try {
            if (policy == null) localStorage.removeItem(preferenceKey);
            else localStorage.setItem(preferenceKey, policy);
          } catch (_) { editorStatus.textContent = 'Applied for this snapshot. Local rule preferences are unavailable.'; }
        }
        if (!state.reportIds.includes(response.report)) {
          state.reportIds.push(response.report);
          state.reportLoad = null;
        }
        state.activePolicyReports.set(env.context, response.report);
        render(response.report, response.assessment, response.policyKind === 'DEFAULT' ? 'Default · dependency cycle rule' : 'Custom repository policy');
      } catch (error) {
        if (env.signal.aborted || current !== version) return;
        if (save) editorStatus.textContent = error.message;
        else detail.replaceChildren(el('p', '', error.message), btn('Retry assessment', () => run(policy, false)));
      } finally {
        if (!env.signal.aborted && current === version) apply.disabled = defaults.disabled = false;
      }
    }
  }

  let architectureChatAssets;
  async function askAboutFinding(item, report, env) {
    if (env.graph !== state.graph || env.context !== state.context) return;
    state.chatFinding = { item, report, graph: env.graph, context: env.context };
    await loadTab('architecture-chat');
    $('panel-architecture-chat').querySelector('textarea')?.focus();
  }
  async function architectureChatView(env) {
    if (!state.capabilities.architectureChatEnabled) {
      return empty('Architecture chat unavailable', 'Start a Workbench server with architecture chat support.');
    }
    architectureChatAssets ??= Promise.all([loadToolAsset('link', '/architecture-chat.css'),
    loadToolAsset('script', '/architecture-chat.js')]).catch(error => { architectureChatAssets = null; throw error; });
    await architectureChatAssets;
    const selected = state.chatFinding;
    const finding = selected?.graph === env.graph && selected.context === env.context ? selected : null;
    const findingRef = finding && { report: finding.report, identity: finding.item.identity, bundle: finding.item.bundleIdentity };
    const seedPrompt = state.chatSeedPrompt || '';
    state.chatSeedPrompt = '';
    return window.mountEvoArchitectureChat({
      graph: env.graph, snapshot: env.snapshot, context: env.context, signal: env.signal,
      job: state.resultJob?.result?.graph === env.graph ? state.resultJob.id : undefined,
      contextLabel: $('context').selectedOptions[0]?.textContent || env.context,
      provider: state.capabilities.architectureChatProvider, finding: findingRef,
      findingLabel: finding?.item.result?.rule?.id || finding?.item.result?.kind,
      agentEnabled: Boolean(state.capabilities?.architectureAgentEnabled),
      seedPrompt,
      send: (input, signal) => api('/graphs/' + env.graph + '/architecture-chat', input, signal, 'POST', 135000),
      stream: state.capabilities?.architectureChatStreamEnabled
        ? (input, signal, handlers) => streamArchitectureChat(env.graph, input, signal, handlers)
        : undefined,
      clearFinding: () => { state.chatFinding = null; return loadTab('architecture-chat'); },
      openFinding: finding && (() => inspectFinding(finding.item, finding.report, env)),
      openRepair: finding && (async () => {
        const args = { identity: finding.item.identity, bundle: finding.item.bundleIdentity, context: env.context };
        const diagnosis = await api('/graphs/' + env.graph + '/reports/' + finding.report + '/diagnosis', { arguments: args }, env.signal);
        if (diagnosis.status !== 'CITATIONS_VALIDATED') return inspectDiagnosis(finding.item, finding.report, env, diagnosis);
        return inspectCapturedRepair(finding.item, finding.report, env, diagnosis);
      }),
      chooseFinding: () => loadTab('policy'),
      openSource: row => inspect('Cited source evidence', 'SOURCE EVIDENCE', async signal => {
        const result = await api('/graphs/' + env.graph + '/queries/evidence', {
          request: { ...M.request(row.context), scope: 'ENTITY' }, arguments: { reference: row.reference }
        }, signal);
        const record = result.items.find(item => item.identity === row.reference);
        if (!record) throw new Error('This source evidence is unavailable in the selected snapshot.');
        return { citation: row, record };
      })
    });
  }
  const views = {
    'architecture-chat': architectureChatView,
    overview(env) {
      const node = el('div'), stats = el('div', 'summary-table');
      const result = state.resultJob?.result;

      // Executive Health & Coverage Hero Strip (truthful trust & coverage denominator)
      const hero = el('section', 'evo-hero-strip');
      hero.setAttribute('aria-label', 'Snapshot trust and coverage overview');
      const completionVal = result?.completion || (state.resultJob?.state || 'CAPTURED');
      const trustTile = el('div', 'evo-hero-metric');
      trustTile.append(
        el('span', 'evo-hero-metric-label', 'Capture Trust Status'),
        append(el('div', 'evo-hero-metric-value'), badge(completionVal)),
        el('span', 'evo-hero-metric-sub', state.resultJob?.mode === 'FAST_ARCHITECTURE' ? 'Quick architecture pass' : 'Deterministic verified capture')
      );
      hero.append(trustTile);

      const gapTotal = result?.gapCount;
      const gapTile = btn('', () => loadTab('coverage'), 'evo-hero-metric is-interactive');
      gapTile.append(
        el('span', 'evo-hero-metric-label', 'Recorded Coverage Gaps'),
        append(el('div', 'evo-hero-metric-value'),
          el('span', '', gapTotal != null ? gapTotal.toLocaleString() : 'Inspect'),
          gapTotal === 0 ? badge('COMPLETE') : gapTotal > 0 ? badge('PARTIAL') : null),
        el('span', 'evo-hero-metric-sub', 'Closed reporting denominator · inspect reasons →')
      );
      hero.append(gapTile);

      const springCount = state.contexts.filter(c => c.identity.startsWith('spring-context:') || state.contextKinds?.get(c.identity) === 'spring.context').length;
      const ctxTile = btn('', () => loadTab(springCount ? 'spring' : 'structure'), 'evo-hero-metric is-interactive');
      ctxTile.append(
        el('span', 'evo-hero-metric-label', 'Analyzed Contexts'),
        el('div', 'evo-hero-metric-value', String(state.contexts.length)),
        el('span', 'evo-hero-metric-sub', springCount ? `${springCount} Spring context${springCount > 1 ? 's' : ''} detected →` : 'Browse modules & symbols →')
      );
      hero.append(ctxTile);

      const repoMapTile = btn('', () => loadTab('repomap'), 'evo-hero-metric is-interactive');
      repoMapTile.append(
        el('span', 'evo-hero-metric-label', 'RepoMap & Source'),
        el('div', 'evo-hero-metric-value', 'PPR'),
        el('span', 'evo-hero-metric-sub', 'Ranked files & captured source viewer →')
      );
      hero.append(repoMapTile);

      const next = append(el('section', 'workbench-next'),
        append(el('div'), el('h2', '', 'Explore this snapshot'),
          el('p', '', 'Open the repository structure, inspect coverage gaps, review a finding, or ask about a design decision.')));
      const routes = append(el('div', 'workbench-next-actions'),
        btn('Browse structure', () => loadTab('structure'), 'button subtle'),
        btn('RepoMap & Source', () => loadTab('repomap'), 'button subtle'),
        btn('Gaps & Coverage', () => loadTab('coverage'), 'button subtle'),
        btn('Review findings', () => loadTab('policy'), 'button subtle'));
      if (state.capabilities?.architectureChatEnabled)
        routes.append(btn('Open architecture chat', () => loadTab('architecture-chat'), 'button primary'));
      next.append(routes);
      const grid = el('div', 'overview-layout'); node.append(hero, next, grid);
      if (env.jobId && result && state.capabilities?.analysisCoverageEnabled) grid.append(coverageCard(env, result));
      if (state.capabilities?.repositoryScoringEnabled) grid.append(repositoryScoreCard(env));
      const health = card('Architecture assessment');
      health.classList.add('overview-context');
      const inventory = append(el('details', 'inventory-details'), el('summary', '', 'Structure counts'), stats);
      let requested = false;
      async function readCounts() {
        requested = true;
        stats.replaceChildren(skeleton(2));
        try {
          const summary = await env.query('summary');
          if (env.signal.aborted) return;
          stats.replaceChildren();
          for (const entry of M.summaryCards(summary)) {
            if (entry.count == null) continue;
            const row = btn('', () => {
              state.structureFocus = entry.kind;
              $('search').value = '';
              return loadTab('structure');
            }, 'summary-row');
            append(row, el('span', '', entry.label), el('strong', '', entry.count.toLocaleString()));
            stats.append(row);
          }
          if (!stats.childElementCount) stats.textContent = 'No structure counts are available for this view.';
        } catch (error) {
          if (!env.signal.aborted) stats.replaceChildren(el('p', 'muted', error.message), btn('Retry counts', readCounts));
        }
      }
      inventory.addEventListener('toggle', () => { if (inventory.open && !requested) readCounts(); });
      health.append(inventory);
      health.append(append(el('div', 'toolbar'), btn('Review score and rules', () => configureRules(false))));
      grid.append(health);
      return node;
    },
    async coverage(env) {
      const node = card('Gaps & Coverage Inspector', 'Every unresolved symbol, missing dependency, opaque condition, and analyzer boundary stays explicitly recorded so partial evidence is never mistaken for absence.');
      const viewSel = selectControl('Gap view', [['CURRENT', 'Current unresolved gaps'], ['HISTORY', 'Historical resolved/superseded'], ['ALL', 'All gap records']], 'CURRENT');
      const filterInput = el('input');
      filterInput.type = 'search';
      filterInput.placeholder = 'Filter by reason code, path or symbol…';
      filterInput.setAttribute('aria-label', 'Filter capability gaps');
      const toolbar = append(el('div', 'toolbar'), field('Graph gap view', viewSel), field('Filter', filterInput));
      if (state.capabilities?.architectureChatEnabled) {
        toolbar.append(btn('Ask AI to prioritize remediation', () => {
          state.chatSeedPrompt = 'Inspect the recorded capability gaps in this snapshot and provide a concrete, prioritized remediation plan to improve semantic coverage.';
          return loadTab('architecture-chat');
        }, 'button primary'));
      }
      const coverageSummaryBox = el('div');
      const groupBox = el('div');
      const recordsBox = el('div');
      node.append(toolbar, coverageSummaryBox, groupBox, recordsBox);

      let selectedReason = '';
      let cachedJobGaps = [];
      let graphGapItems = [];
      let version = 0;

      function extractGapCode(item) {
        return item.reasonCode || item.properties?.reasonCode || item.properties?.reason || item.reason || (item.kind && item.kind !== 'CAPABILITY_GAP' ? item.kind : 'CAPABILITY_GAP');
      }

      function renderGroupCards(reasonPairs, headingText) {
        if (!reasonPairs.length) return;
        const grid = el('div', 'gaps-summary-grid');
        for (const { code, count } of reasonPairs) {
          const info = M.humanReason(code);
          const bucket = M.classifyGapObligation ? M.classifyGapObligation(code) : { label: 'Boundary' };
          const gCard = btn('', () => {
            selectedReason = selectedReason === code ? '' : code;
            filterInput.value = selectedReason;
            renderFiltered();
            for (const c of grid.children) c.classList.toggle('is-selected', c.dataset.code === selectedReason);
          }, 'gap-group-card' + (selectedReason === code ? ' is-selected' : ''));
          gCard.dataset.code = code;
          append(gCard,
            append(el('div', 'gap-group-head'), el('strong', '', info.title), el('span', 'gap-group-count', count.toLocaleString())),
            el('span', 'gap-code-chip', `${code} · ${bucket.label}`),
            el('p', 'small muted', info.explanation),
            el('p', 'gap-remediation-tip', 'Action: ' + info.action)
          );
          grid.append(gCard);
        }
        groupBox.replaceChildren(el('h3', '', headingText), grid);
      }

      // Load job coverage obligations summary when jobId is available (always clears skeleton in finally!)
      if (env.jobId && state.capabilities?.analysisCoverageEnabled) {
        coverageSummaryBox.replaceChildren(skeleton(2));
        api('/analysis/jobs/' + env.jobId + '/coverage', undefined, env.signal, 'GET').then(cov => {
          if (env.signal.aborted) return;
          coverageSummaryBox.replaceChildren();
          const ob = cov.obligations || {};
          const obSum = (ob.environment || 0) + (ob.authority || 0) + (ob.boundary || 0) + (ob.source || 0);
          coverageSummaryBox.append(el('p', 'small muted', `Completion: ${cov.completion || 'PARTIAL'} · ${Number(cov.totalGaps || 0).toLocaleString()} total gaps (${Number(cov.counted || 0).toLocaleString()} ledger records classified)`));
          if (obSum > 0) {
            const barWrap = el('div', 'obligation-bar-wrap');
            const bar = el('div', 'obligation-bar');
            const legend = el('div', 'obligation-legend');
            for (const [k, lbl, cls] of [['environment', 'Environment & Build', 'is-env'], ['authority', 'Authority / Policy', 'is-auth'], ['boundary', 'Analyzer Boundary', 'is-boundary'], ['source', 'Source / Dynamic', 'is-source']]) {
              const count = ob[k] || 0;
              if (!count) continue;
              const seg = el('span', 'obligation-seg ' + cls);
              seg.style.width = ((count / obSum) * 100).toFixed(1) + '%';
              seg.title = `${lbl}: ${count}`;
              bar.append(seg);
              legend.append(append(el('span', 'obligation-legend-item'), el('span', 'obligation-dot ' + cls), el('span', '', `${lbl}: ${count.toLocaleString()}`)));
            }
            if (bar.childElementCount) {
              barWrap.append(bar, legend);
              coverageSummaryBox.append(barWrap);
            }
          }
          if (Array.isArray(cov.topReasons) && cov.topReasons.length) {
            renderGroupCards(cov.topReasons.map(r => ({ code: r.reason, count: r.count })), 'Gap Reasons & Remediation Guide');
          }
        }).catch(() => {
          if (!env.signal.aborted) coverageSummaryBox.replaceChildren();
        }).finally(() => {
          if (!env.signal.aborted && coverageSummaryBox.querySelector('.evo-skeleton-stack')) {
            coverageSummaryBox.replaceChildren();
          }
        });
      }

      function renderFiltered() {
        const term = filterInput.value.trim().toLowerCase();
        // Prioritize structured job CapabilityGapRecords (with real reasonCode/subject/mechanism) before generic graph CAPABILITY_GAP nodes
        const seen = new Set(cachedJobGaps.map(g => g.identity).filter(Boolean));
        const extraGraphGaps = graphGapItems.filter(gg => {
          if (gg.identity && seen.has(gg.identity)) return false;
          const code = extractGapCode(gg);
          // When structured job gaps are present, avoid drowning them in bare uncoded CAPABILITY_GAP placeholders
          if (cachedJobGaps.length && code === 'CAPABILITY_GAP' && !gg.qualifiedName && !gg.path) return false;
          return true;
        });
        const combined = [...cachedJobGaps, ...extraGraphGaps];
        const visible = combined.filter(item => {
          if (!term) return true;
          return JSON.stringify(item).toLowerCase().includes(term);
        });
        const list = el('div', 'list');
        if (!visible.length) {
          recordsBox.replaceChildren(empty(term ? 'No matching gap records' : 'No recorded capability gaps',
            term ? 'Clear or loosen the filter to inspect all recorded gaps.' : 'No gaps were recorded for the selected context and view.'));
          return;
        }
        visible.slice(0, 80).forEach(item => {
          const code = extractGapCode(item);
          const info = M.humanReason(code);
          const bucket = M.classifyGapObligation ? M.classifyGapObligation(code) : null;
          const subjectText = item.subject || item.path || item.qualifiedName || item.spelling || item.properties?.subject || '';
          const cleanSubject = /^sha256:[0-9a-f]{16,}$/i.test(subjectText) ? M.shortDigest(subjectText) : subjectText;
          const titleText = info.title + (cleanSubject && !info.title.includes(cleanSubject) ? ' — ' + cleanSubject : '');
          const subtitle = [bucket?.label, item.mechanismCategory || item.properties?.mechanismCategory, code].filter(Boolean).join(' · ');
          list.append(row({
            identity: item.identity,
            label: titleText,
            kind: code
          }, () => inspect(info.title, 'CAPABILITY GAP', { humanExplanation: info, obligation: bucket?.label, record: item },
            item.path ? [{ label: 'Open captured source', run: () => openCapturedSourceModal(env.graph, item.path) }] : []), subtitle));
        });
        recordsBox.replaceChildren(el('h3', '', `Recorded Gaps (${visible.length}${visible.length > 80 ? ' · showing first 80' : ''})`), list);
      }

      async function loadGraphGaps() {
        const current = ++version;
        recordsBox.replaceChildren(skeleton(3));
        try {
          const [graphRes, jobRes] = await Promise.all([
            env.query('gaps', { view: viewSel.value }, { size: 50 }).catch(() => ({ items: [] })),
            env.jobId && !cachedJobGaps.length
              ? api('/analysis/jobs/' + env.jobId + '/gaps', { size: 50 }, env.signal, 'POST').catch(() => ({ items: [] }))
              : Promise.resolve(null)
          ]);
          if (env.signal.aborted || current !== version) return;
          graphGapItems = graphRes?.items || [];
          if (jobRes && Array.isArray(jobRes.items)) {
            cachedJobGaps = jobRes.items;
            if (!groupBox.childElementCount && cachedJobGaps.length) {
              const groups = M.summarizeGaps(cachedJobGaps);
              renderGroupCards(groups.map(g => ({ code: g.code, count: g.count })), 'Observed Gap Categories');
            }
          }
          renderFiltered();
        } catch (err) {
          if (!env.signal.aborted && current === version) {
            recordsBox.replaceChildren(empty('Graph gap query unavailable', err.message), btn('Retry', loadGraphGaps));
          }
        }
      }
      viewSel.addEventListener('change', () => safe(loadGraphGaps));
      filterInput.addEventListener('input', renderFiltered);
      await loadGraphGaps();
      return node;
    },
    async repomap(env) {
      const node = card('RepoMap (PPR) & Captured Source', 'Personalized PageRank architectural ranking across captured dependency edges paired with an interactive captured-source inspector.');
      const modeBar = el('div', 'chip-bar');
      const mapTabBtn = btn('PPR Ranked RepoMap', () => setMode('map'), 'chip active');
      const filesTabBtn = btn('All Captured Files', () => setMode('files'), 'chip');
      modeBar.append(mapTabBtn, filesTabBtn);

      const seedInput = el('input');
      seedInput.type = 'search';
      seedInput.placeholder = 'Optional PPR seeds (comma-separated paths or class names)…';
      seedInput.setAttribute('aria-label', 'RepoMap PPR seeds');
      const maxFilesSel = selectControl('Max ranked files', [['15', 'Top 15 files'], ['25', 'Top 25 files'], ['50', 'Top 50 files'], ['100', 'Top 100 files']], '25');
      const refreshBtn = btn('Rank map', () => loadMap(), 'button primary');
      const mapToolbar = append(el('div', 'toolbar'), field('Focus seeds', seedInput), field('Depth', maxFilesSel), refreshBtn);

      const fileSearch = el('input');
      fileSearch.type = 'search';
      fileSearch.placeholder = 'Filter captured files by path…';
      fileSearch.setAttribute('aria-label', 'Search captured files');
      const langSel = selectControl('Language filter', [['', 'All languages']], '');
      const filesToolbar = append(el('div', 'toolbar'), field('Path filter', fileSearch), field('Language', langSel), btn('Search files', () => loadFiles(0), 'button primary'));
      filesToolbar.hidden = true;

      const metaBar = el('p', 'small muted');
      const layout = el('div', 'repomap-layout');
      const leftPane = el('div');
      const rightPane = el('div');
      rightPane.append(empty('Select a file to inspect source', 'Choose any ranked RepoMap file or captured file on the left to read its captured source with line numbers.'));
      layout.append(leftPane, rightPane);
      node.append(modeBar, mapToolbar, filesToolbar, metaBar, layout);

      let activeMode = 'map', activePath = '', mapVersion = 0, fileVersion = 0, sourceVersion = 0;

      function setMode(next) {
        activeMode = next;
        mapTabBtn.classList.toggle('active', next === 'map');
        filesTabBtn.classList.toggle('active', next === 'files');
        mapToolbar.hidden = next !== 'map';
        filesToolbar.hidden = next !== 'files';
        if (next === 'map') safe(loadMap);
        else safe(() => loadFiles(0));
      }

      async function loadSource(path, startLine = 1, endLine = 200) {
        activePath = path;
        const current = ++sourceVersion;
        for (const c of leftPane.querySelectorAll('.repomap-file-card')) {
          c.classList.toggle('is-selected', c.dataset.path === path);
        }
        rightPane.replaceChildren(skeleton(5));
        try {
          const data = await api('/graphs/' + env.graph + '/captured-source', { path, startLine, endLine }, env.signal);
          if (env.signal.aborted || current !== sourceVersion) return;
          if (!data?.found) {
            rightPane.replaceChildren(empty(data?.ambiguous ? 'Ambiguous file path' : 'Source not readable',
              data?.ambiguous ? 'Select one of the matching captured paths.' : 'Binary or uncaptured file bytes.'));
            return;
          }
          rightPane.replaceChildren(renderSourceCodeBlock(data, null, (ns, ne) => loadSource(path, ns, ne)));
        } catch (err) {
          if (!env.signal.aborted && current === sourceVersion) {
            rightPane.replaceChildren(empty('Could not read captured source', err.message));
          }
        }
      }

      async function loadMap() {
        const current = ++mapVersion;
        leftPane.replaceChildren(skeleton(4));
        metaBar.textContent = 'Computing deterministic Personalized PageRank RepoMap…';
        try {
          const seeds = seedInput.value.split(',').map(s => s.trim()).filter(Boolean);
          const data = await api('/graphs/' + env.graph + '/repo-map', {
            context: defaultDependencyContext(env),
            ...(seeds.length ? { seeds } : {}),
            maxFiles: Number(maxFilesSel.value) || 25,
            maxTokens: 6000
          }, env.signal);
          if (env.signal.aborted || current !== mapVersion) return;
          metaBar.textContent = `${data.algorithm || 'personalized-pagerank-v1'} · ${data.totalFiles ?? data.files?.length ?? 0} files · ${data.totalNodes ?? 0} nodes · ${data.totalEdges ?? 0} edges · ~${data.estimatedTokens ?? 0} tokens${data.truncated ? ' (bounded)' : ''}`;
          const files = data.files || [];
          if (!files.length) {
            leftPane.replaceChildren(empty('No ranked files in this context', 'Switch to All Captured Files or select another analysis context.'));
            return;
          }
          const maxScore = Math.max(0.0001, ...files.map(f => Number(f.score) || 0));
          const list = el('div', 'repomap-file-list');
          files.forEach((f, idx) => {
            const cardBtn = btn('', () => loadSource(f.path, 1, 200), 'repomap-file-card' + (activePath === f.path ? ' is-selected' : ''));
            cardBtn.dataset.path = f.path;
            const head = append(el('div', 'repomap-file-head'),
              el('span', 'repomap-rank', '#' + (f.rank ?? (idx + 1))),
              el('span', 'repomap-path', f.path),
              f.language ? el('span', 'badge', f.language) : null,
              f.gapCount ? el('span', 'badge warn', f.gapCount + ' gap' + (f.gapCount === 1 ? '' : 's')) : null
            );
            const bar = el('div', 'repomap-score-track');
            const fill = el('div', 'repomap-score-fill');
            fill.style.width = Math.max(4, Math.round(((Number(f.score) || 0) / maxScore) * 100)) + '%';
            bar.append(fill);
            const meta = el('div', 'repomap-meta-row', `Score ${(Number(f.score) || 0).toFixed(4)} · In ${f.inDegree ?? 0} · Out ${f.outDegree ?? 0}` + (f.endpoints?.length ? ` · ${f.endpoints.length} endpoints` : ''));
            cardBtn.append(head, bar, meta);
            if (Array.isArray(f.declarations) && f.declarations.length) {
              const decls = el('div', 'repomap-decl-chips');
              f.declarations.slice(0, 6).forEach(d => decls.append(el('span', 'repomap-decl-chip', d.name || d.kind)));
              cardBtn.append(decls);
            }
            list.append(cardBtn);
          });
          leftPane.replaceChildren(list);
          if (data.renderedMap) {
            const rawDetails = append(el('details', 'inventory-details'), el('summary', '', 'Rendered text RepoMap (LLM prompt format)'),
              append(el('div', 'remedy-patch-actions'), btn('Copy rendered map', async () => {
                try { await navigator.clipboard?.writeText(data.renderedMap); window.EvoToast?.push({ kind: 'ok', title: 'RepoMap copied' }); } catch (_) {}
              }, 'button subtle')),
              el('pre', 'repomap-rendered-pre', data.renderedMap));
            leftPane.append(rawDetails);
          }
          if (!activePath && files[0]?.path) loadSource(files[0].path, 1, 200);
        } catch (err) {
          if (!env.signal.aborted && current === mapVersion) {
            metaBar.textContent = '';
            leftPane.replaceChildren(empty('RepoMap unavailable', err.message), btn('Browse All Captured Files', () => setMode('files'), 'button subtle'));
          }
        }
      }

      async function loadFiles(offset = 0) {
        const current = ++fileVersion;
        leftPane.replaceChildren(skeleton(4));
        metaBar.textContent = 'Reading captured snapshot inventory…';
        try {
          const data = await api('/graphs/' + env.graph + '/captured-files', {
            ...(fileSearch.value.trim() ? { query: fileSearch.value.trim() } : {}),
            ...(langSel.value ? { language: langSel.value } : {}),
            limit: 60,
            offset
          }, env.signal);
          if (env.signal.aborted || current !== fileVersion) return;
          if (data.languages && langSel.options.length <= 1) {
            for (const [lang, count] of Object.entries(data.languages).sort((a, b) => b[1] - a[1])) {
              langSel.append(new Option(`${lang} (${count})`, lang));
            }
          }
          metaBar.textContent = `${data.matchedFiles ?? 0} matching files of ${data.totalFiles ?? 0} captured files`;
          const files = data.files || [];
          if (!files.length) {
            leftPane.replaceChildren(empty('No captured files matched', 'Try clearing the path or language filter.'));
            return;
          }
          const list = el('div', 'repomap-file-list');
          files.forEach(f => {
            const cardBtn = btn('', () => f.readable === false
              ? window.EvoToast?.push({ kind: 'warn', title: 'Binary or unreadable file', detail: f.path })
              : loadSource(f.path, 1, 200), 'repomap-file-card' + (activePath === f.path ? ' is-selected' : ''));
            cardBtn.dataset.path = f.path;
            const head = append(el('div', 'repomap-file-head'),
              el('span', 'repomap-path', f.path),
              f.language ? el('span', 'badge', f.language) : null,
              f.readable === false ? el('span', 'badge warn', 'Binary') : null
            );
            const kb = f.size != null ? (f.size < 1024 ? f.size + ' B' : (f.size / 1024).toFixed(1) + ' KB') : '';
            cardBtn.append(head, el('div', 'repomap-meta-row', [kb, f.sourceKind, f.digest ? M.shortDigest(f.digest) : null].filter(Boolean).join(' · ')));
            list.append(cardBtn);
          });
          const pager = el('div', 'remedy-patch-actions');
          if (offset > 0) pager.append(btn('← Previous', () => loadFiles(Math.max(0, offset - 60)), 'button subtle'));
          if (offset + files.length < (data.matchedFiles || 0)) pager.append(btn('Next →', () => loadFiles(offset + 60), 'button subtle'));
          leftPane.replaceChildren(list, pager);
        } catch (err) {
          if (!env.signal.aborted && current === fileVersion) {
            metaBar.textContent = '';
            leftPane.replaceChildren(empty('Captured files unavailable', err.message));
          }
        }
      }

      maxFilesSel.addEventListener('change', () => safe(loadMap));
      langSel.addEventListener('change', () => safe(() => loadFiles(0)));
      await loadMap();
      return node;
    },
    async structure(env) {
      const node = card('Repository structure', 'Open a module, package or class to browse its recorded contents. Use Details for source locations, dependencies and impact.');
      const levelOptions = [['MODULE', 'Modules'], ['PACKAGE', 'Packages'], ['TYPE', 'Classes'], ['MEMBER', 'Members'], ['SOURCE_DOCUMENT', 'Source files'], ['SOURCE_ROOT', 'Source roots']];
      const focus = state.structureFocus; state.structureFocus = null;
      const level = selectControl('Browse level', levelOptions, focus || ($('search').value.trim() ? 'TYPE' : 'MODULE'));
      const population = selectControl('Symbol population', [['all', 'All captured origins'], ['PROJECT', 'Project code'], ['JDK', 'JDK types'], ['DEPENDENCY', 'External dependencies'], ['SYNTHETIC', 'Synthetic evidence']]);
      const sourceSet = selectControl('Source set', [['', 'All source sets'], ['MAIN', 'Main'], ['TEST', 'Test'], ['GENERATED_MAIN', 'Generated main'], ['GENERATED_TEST', 'Generated test']]);
      const quickPills = el('div', 'chip-bar');
      const syncPills = () => { [...quickPills.children].forEach(c => c.classList.toggle('active', c.dataset.level === level.value)); };
      for (const [val, text] of levelOptions) {
        const pill = btn(text, () => { level.value = val; trail = []; syncPills(); return load(); }, 'chip');
        pill.dataset.level = val; quickPills.append(pill);
      }
      const breadcrumbs = el('nav', 'breadcrumbs'); breadcrumbs.setAttribute('aria-label', 'Structure location');
      const selection = el('div', 'selection-bar'), results = el('div');
      node.append(quickPills, append(el('div', 'toolbar'), field('Browse', level), field('Origin', population), field('Source set', sourceSet)), breadcrumbs, selection, results);
      let version = 0, trail = !focus && state.explorer?.context === env.context ? state.explorer.trail : [];
      const memberKinds = ['METHOD', 'CONSTRUCTOR', 'FIELD', 'PARAMETER', 'TYPE_PARAMETER', 'RECORD_COMPONENT', 'INITIALIZER', 'LAMBDA'];
      async function load() {
        syncPills();
        const current = ++version, search = $('search').value.trim(), target = el('div'), parent = search ? null : trail.at(-1);
        state.explorer = { context: env.context, trail }; breadcrumbs.replaceChildren(btn('Repository', () => { trail = []; $('search').value = ''; return load(); }, 'text-button'));
        trail.forEach((item, index) => breadcrumbs.append(el('span', '', '/'), btn(M.label(item), () => { trail = trail.slice(0, index + 1); $('search').value = ''; return load(); }, 'text-button')));
        selection.replaceChildren(); if (parent) selection.append(el('strong', '', M.label(parent)), btn('Details', () => inspectEntity(parent, env)), btn('Back', () => { trail.pop(); return load(); }));
        if (search) selection.append(el('p', 'muted small', 'Search results across the selected context: ' + search), btn('Clear search', () => { $('search').value = ''; return load(); }));
        results.replaceChildren(empty('Loading…', 'Reading matching symbols.'));
        const overrides = {
          scope: parent ? 'ENTITY' : ['MODULE', 'PACKAGE', 'TYPE'].includes(level.value) ? level.value : 'ENTITY',
          population: { nodeKinds: parent ? [] : level.value === 'MEMBER' ? memberKinds : [level.value], origins: population.value === 'all' ? [] : [population.value], sourceSets: sourceSet.value ? [sourceSet.value] : [] }
        };
        try {
          await pagedList(target, env, cursor => env.query(parent ? 'children' : 'entities', parent ? { identity: parent.identity } : { search }, { size: 50, ...(cursor ? { cursor } : {}) }, overrides), item => {
            const entry = el('div', 'explorer-row'); const navigable = ['MODULE', 'PACKAGE', 'TYPE', 'METHOD', 'CONSTRUCTOR', 'SOURCE_ROOT'].includes(item.kind);
            entry.append(row(item, () => { if (navigable) { trail.push(item); $('search').value = ''; return load(); } return inspectEntity(item, env); }));
            entry.append(btn('Details', () => inspectEntity(item, env), 'button subtle')); return entry;
          }, 'No matching contents', 'Try another origin, source set or browse level.');
          if (!env.signal.aborted && current === version) results.replaceChildren(target);
        } catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Symbols unavailable', error.message), btn('Retry', load)); }
      }
      level.addEventListener('change', () => { trail = []; safe(load); }); population.addEventListener('change', () => safe(load)); sourceSet.addEventListener('change', () => safe(load)); await load(); return node;
    },
    async policy(env) {
      const node = card('Rules & findings', 'Inspect the evaluated rules and their evidence. An absent report is not a clean architecture verdict.');
      if (!env.reportIds.length) { node.append(empty('No policy report for this context', 'Add architecture policy YAML when connecting the repository, then run a new analysis.'), btn('Configure rules & scoring', configureRules, 'button primary')); return node; }
      const toolbar = el('div', 'toolbar'), reports = el('select'), kinds = el('select'); reports.setAttribute('aria-label', 'Policy report'); kinds.setAttribute('aria-label', 'Rules or findings');
      env.reportIds.forEach((report, index) => { const option = el('option', '', 'Policy report ' + (index + 1)); option.value = report.identity; reports.append(option); });
      const active = state.activePolicyReports?.get(env.context);
      if (env.reportIds.some(report => report.identity === active)) reports.value = active;
      for (const op of ['findings', 'rules']) { const option = el('option', '', op === 'rules' ? 'Evaluated rules' : 'Observed findings'); option.value = op; kinds.append(option); }
      toolbar.append(reports, kinds); node.append(toolbar); const results = el('div'); node.append(results); let version = 0;
      async function load() {
        const current = ++version, id = reports.value, op = kinds.value, target = el('div');
        results.replaceChildren(empty('Loading…', 'Reading the selected report.'));
        try {
          await pagedList(target, env, cursor => env.report(id, op, {}, { size: 50, ...(cursor ? { cursor } : {}) }), item => {
            const finding = item.result, rule = finding.rule?.header || finding.rule;
            const label = rule?.name || rule?.id || finding.kind || 'Evaluated rule';
            const r = row({ label, kind: finding.classification || finding.status }, () => {
              if (op === 'findings') {
                if ($('remediation-active-workflow')) return selectRemediationFinding(item, id, env);
                return inspectFinding(item, id, env);
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
              if (firstRow && $('remediation-active-workflow') && !state.selectedFinding) {
                firstRow.click();
              }
            }
          }
        } catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Report unavailable', error.message), btn('Retry', load)); }
      }
      reports.addEventListener('change', () => safe(load)); kinds.addEventListener('change', () => safe(load)); await load(); return node;
    },
    async spring(env) {
      const node = card('Spring Architecture & Configuration Space', 'Inspect detected Spring beans, injection points, HTTP endpoints, registration decisions, and symbolic configuration conditions.');
      const springContexts = state.contexts.filter(c => c.identity.startsWith('spring-context:') || state.contextKinds?.get(c.identity) === 'spring.context');
      const initialCtx = M.findPreferredSpringContext
        ? M.findPreferredSpringContext(state.contexts, env.context, state.contextKinds)
        : (springContexts[0]?.identity || env.context);
      let activeCtx = initialCtx;
      const ctxSelect = springContexts.length > 1
        ? selectControl('Spring context', springContexts.map(c => [c.identity, state.contextLabels?.get(c.identity) || c.identity]), activeCtx)
        : null;
      const categories = [
        ['components', 'Beans & Injection'],
        ['endpoints', 'HTTP Endpoints'],
        ['registration', 'Registration Decisions'],
        ['configurations', 'Conditions & Config Space']
      ];
      const select = selectControl('Spring evidence category', categories, 'components');
      const pills = el('div', 'chip-bar');
      const syncPills = () => { [...pills.children].forEach(c => c.classList.toggle('active', c.dataset.cat === select.value)); };
      for (const [value, title] of categories) {
        const chip = btn(title, () => { select.value = value; syncPills(); return load(); }, 'chip' + (value === 'components' ? ' active' : ''));
        chip.dataset.cat = value;
        pills.append(chip);
      }
      const toolbar = append(el('div', 'toolbar'),
        ctxSelect ? field('Spring context', ctxSelect) : null,
        field('View', select),
        btn('Compare configurations (MUST / MAY) →', () => loadTab('configurations'), 'button subtle'));
      node.append(pills, toolbar);
      const results = el('div'); node.append(results); let version = 0;
      async function load() {
        syncPills();
        const current = ++version, category = select.value, target = el('div');
        results.replaceChildren(skeleton(3));
        try {
          const queryCtx = ctxSelect ? ctxSelect.value : activeCtx;
          await pagedList(target, env, cursor => env.query(
            category === 'configurations' ? 'configurations' : 'spring',
            category === 'configurations' ? {} : {
              kinds: category === 'endpoints'
                ? ['ENDPOINT']
                : category === 'registration'
                  ? ['REGISTRATION_EVENT', 'REGISTRATION_TRANSITION']
                  : ['BEAN_PRODUCER', 'BEAN_DEFINITION_CANDIDATE', 'INJECTION_POINT', 'INJECTION_USE']
            },
            { size: 50, ...(cursor ? { cursor } : {}) },
            { context: queryCtx }
          ), item => row(item, () => inspectEntity(item, { ...env, context: queryCtx, request: M.request(queryCtx) })),
            'No Spring evidence available in this context', 'Select a Spring module context or inspect captured configurations.');
          if (!env.signal.aborted && current === version) results.replaceChildren(target);
        } catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Spring evidence unavailable', error.message), btn('Retry', load)); }
      }
      if (ctxSelect) ctxSelect.addEventListener('change', () => { activeCtx = ctxSelect.value; safe(load); });
      select.addEventListener('change', () => safe(load)); await load(); return node;
    },
    async configurations(env) {
      const node = card('Compare configurations', 'Compare dependencies within this captured analysis. Recorded configurations and modeled examples are distinct from a running application.');
      node.append(el('p', 'notice small', '“All observed” can include alternatives that never coexist. “Required” keeps dependencies present in every modeled configuration.'));
      const options = [['MUST', 'Required in every configuration (MUST)'], ['MAY', 'Conditional dependencies (MAY)'], ['UNKNOWN_OVERLAY', 'Uncertain dependencies (UNKNOWN)'], ['BOUNDED_UNION', 'All observed dependencies (UNION)']];
      const saved = state.comparison?.graph === env.graph && state.comparison?.context === env.context ? state.comparison : null;
      const a = selectControl('View A', options, 'MUST'), b = selectControl('View B', options, 'MAY'), scope = selectControl('Comparison scope', [['TYPE', 'Classes'], ['PACKAGE', 'Packages'], ['MODULE', 'Modules'], ['SPRING_COMPONENT', 'Spring components']], saved?.scope || (env.springContext ? 'SPRING_COMPONENT' : 'TYPE'));
      const toolbar = append(el('div', 'toolbar'), field('View A', a), field('View B', b), field('Scope', scope)); node.append(toolbar);
      let choices = [], cursor, version = 0; const choiceStatus = el('p', 'small muted'), results = el('div'); node.append(choiceStatus, results);
      const more = btn('Load more configurations', () => loadChoices(true));
      async function loadChoices(continuing = false) {
        more.disabled = true;
        try {
          const page = await env.query('realizations', {}, { size: 20, ...(continuing && cursor ? { cursor } : {}) }); if (env.signal.aborted) return;
          choices.push(...page.items); cursor = page.continuation;
          for (const select of [a, b]) for (const [index, item] of page.items.entries()) {
            const settings = Object.entries(item.profiles || {}).map(([name, active]) => name + ': ' + (active ? 'on' : 'off')).join(', ');
            const option = el('option', '', (item.kind === 'RECORDED_WORLD' ? 'Recorded configuration ' : 'Modeled example ') + (choices.length - page.items.length + index + 1) + (settings ? ' · baseline ' + settings : '')); option.value = item.identity; select.append(option);
          }
          choiceStatus.textContent = choices.length ? 'Choose two configurations to compare.' : 'Choose a Spring context to compare configurations.';
          more.hidden = !cursor;
        } finally { more.disabled = false; }
      }
      function override(value) { return options.some(([id]) => id === value) ? { projection: value } : M.realizationRequest(choices.find(c => c.identity === value)); }
      async function compare() {
        const current = ++version, left = override(a.value), right = override(b.value), selectedScope = scope.value, labels = [a.selectedOptions[0].textContent, b.selectedOptions[0].textContent];
        state.comparison = { graph: env.graph, context: env.context, a: a.value, b: b.value, scope: selectedScope };
        results.replaceChildren(empty('Comparing…', 'Reading two bounded canonical dependency views.'));
        try {
          const values = await Promise.all([env.query('edges', {}, { size: 20 }, { scope: selectedScope, relationshipKinds: relationshipKinds(selectedScope), ...left }), env.query('edges', {}, { size: 20 }, { scope: selectedScope, relationshipKinds: relationshipKinds(selectedScope), ...right })]);
          await resolveLabels(values.flatMap(value => value.items.flatMap(edge => [edge.source, edge.target])), env);
          if (env.signal.aborted || current !== version) return;
          const shown = [...values];
          function render() {
            results.replaceChildren(); const summary = el('div', 'comparison-summary'); results.append(summary);
            for (const [index, value] of shown.entries()) summary.append(append(el('div', 'surface'), el('h3', '', index ? 'View B' : 'View A'), el('p', 'small muted', labels[index]), el('p', 'small', M.pageInfo(value)), btn('Inspect view', () => inspect(index ? 'View B' : 'View A', 'CONFIGURATION PROJECTION', value), 'text-button')));
            const table = el('table', 'comparison-table'), head = append(el('tr'), el('th', '', 'Dependency'), el('th', '', 'View A'), el('th', '', 'View B')), body = el('tbody'); table.append(el('caption', '', 'Dependencies in the two selected views'), append(el('thead'), head), body); head.querySelectorAll('th').forEach(cell => cell.scope = 'col'); results.append(append(el('div', 'table-scroll'), table));
            const rows = M.compareEdges(shown[0], shown[1]);
            for (const value of rows) { const edge = value.edge; body.append(append(el('tr'), append(el('td'), btn((state.labels.get(edge.source) || M.shortDigest(edge.source)) + ' → ' + (state.labels.get(edge.target) || M.shortDigest(edge.target)), () => inspect('Dependency evidence', 'DEPENDENCY', edge, [{ label: 'Source details', run: async () => { const value = await env.query('entity', { identity: edge.source }); if (!env.signal.aborted && value.items[0]) return inspectEntity(value.items[0], env); } }, { label: 'Target details', run: async () => { const value = await env.query('entity', { identity: edge.target }); if (!env.signal.aborted && value.items[0]) return inspectEntity(value.items[0], env); } }]), 'text-button')), el('td', '', badge(value.a)), el('td', '', badge(value.b)))); }
            if (!rows.length) results.append(empty('No dependency rows returned', 'Try another configuration or scope.'));
            const controls = el('div', 'toolbar'); results.append(controls);
            for (const index of [0, 1]) if (shown[index].continuation) controls.append(btn('Load more from ' + (index ? 'B' : 'A'), async () => {
              const next = await env.query('edges', {}, { size: 20, cursor: shown[index].continuation }, { scope: selectedScope, relationshipKinds: relationshipKinds(selectedScope), ...(index ? right : left) });
              await resolveLabels(next.items.flatMap(edge => [edge.source, edge.target]), env);
              if (env.signal.aborted || current !== version) return;
              shown[index] = { ...next, items: [...shown[index].items, ...next.items] }; render();
            }));
            controls.append(btn('Download comparison JSON', () => download({ scope: selectedScope, viewA: { request: left, result: shown[0] }, viewB: { request: right, result: shown[1] } }, 'configuration-views.json')));
            for (const [index, selection] of [left, right].entries()) controls.append(btn('Open ' + (index ? 'B' : 'A') + ' graph', () => { state.graphFocus = { scope: selectedScope, ...selection }; return loadTab('graph'); }));
            results.append(el('p', 'muted small', 'Only loaded rows are compared. “Not established” means evidence or remaining pages prevent an absence claim. This compares configurations of one snapshot, not repository history.'));
          } render();
        } catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Comparison unavailable', error.message), btn('Retry', compare)); }
      }
      toolbar.append(btn('Compare views', compare, 'button primary'), more, btn('Configuration evidence', () => inspect('Configuration evidence', 'CONFIGURATION', (signal, cursor) => api('/graphs/' + env.graph + '/queries/configurations', { request: env.request, arguments: {}, page: { size: 20, ...(cursor ? { cursor } : {}) } }, signal))));
      await loadChoices(); if (saved) { for (const [select, value] of [[a, saved.a], [b, saved.b]]) if ([...select.options].some(option => option.value === value)) select.value = value; } await compare(); return node;
    },
    async metrics(env) {
      const node = el('div');
      if (state.capabilities?.repositoryScoringEnabled) {
        node.append(append(el('div', 'content-grid'), repositoryScoreCard(env, true), architectureScoreCard(env)));
      } else {
        node.append(architectureScoreCard(env));
      }
      const catalog = await env.query('metric-catalog', {}, { size: 50 }), metricCard = card('Architecture metrics', 'Choose a metric and scope.'); metricCard.style.marginTop = '20px'; node.append(metricCard);
      const metricNames = { 'metric.ca': 'Incoming coupling (Ca)', 'metric.ce': 'Outgoing coupling (Ce)', 'metric.scc': 'Connected components (SCC)', 'metric.physical-loc': 'Physical lines of code (LOC)', 'metric.inventory': 'Captured inventory', 'metric.fan-in': 'Incoming dependencies (Fan-in)', 'metric.fan-out': 'Outgoing dependencies (Fan-out)', 'metric.hotspots': 'Dependency hotspots', 'metric.crossings': 'Layer boundary crossings', 'metric.operational': 'Measured runtime', 'metric.spring': 'Spring inventory' };
      const metric = selectControl('Metric', catalog.items.filter(id => id !== 'metric.quality').map(id => [id, metricNames[id] || id.replace(/^metric\./, '').replaceAll('-', ' ').replace(/^./, letter => letter.toUpperCase())]), env.springContext ? 'metric.spring' : 'metric.inventory');
      const subject = state.metricSubject; state.metricSubject = null;
      const scope = selectControl('Metric scope', [['TYPE', 'Classes'], ['PACKAGE', 'Packages'], ['MODULE', 'Modules'], ['SPRING_COMPONENT', 'Spring components'], ['ENTITY', 'All entities']], subject ? (['TYPE', 'PACKAGE', 'MODULE'].includes(subject.kind) ? subject.kind : 'ENTITY') : (env.springContext ? 'SPRING_COMPONENT' : 'TYPE'));
      const subjectLabel = el('p', 'small muted', subject ? 'Selected subject: ' + M.label(subject) : 'Subject: the complete selected analysis context. Choose an entity’s Metric details to drill down.'); metricCard.append(subjectLabel);
      let selectedSubject = subject?.identity || env.context, version = 0;
      const toolbar = append(el('div', 'toolbar'), field('Metric', metric), field('Scope', scope)), definition = el('div'), result = el('div'); metricCard.append(toolbar, definition, result);
      async function evaluate() {
        const current = ++version; result.replaceChildren(empty('Reading metric…', 'Computing the bounded metric in the canonical service.'));
        try {
          const spec = await env.query('metric-definition', { metric: metric.value }); if (env.signal.aborted || current !== version) return;
          const entry = spec.items[0]; if (entry) {
            for (const option of scope.options) option.disabled = !entry.scopes.includes(option.value);
            if (!entry.scopes.includes(scope.value)) { scope.value = [...scope.options].find(option => !option.disabled).value; selectedSubject = env.context; subjectLabel.textContent = 'Using the selected context at a scope supported by this metric.'; }
          }
          const value = await env.query('metric', { metric: metric.value, subject: selectedSubject }, undefined, { scope: scope.value, relationshipKinds: relationshipKinds(scope.value) }); if (env.signal.aborted || current !== version) return;
          const data = value.items[0]; definition.replaceChildren(); if (entry) definition.append(el('p', 'muted', entry.meaning), append(el('details'), el('summary', '', 'Definition, formula & population'), el('p', '', entry.formula), el('p', 'muted small', entry.population), btn('Full definition', () => inspect('Metric definition', 'METRIC DEFINITION', entry), 'text-button')));
          result.replaceChildren(); if (!data) { result.append(empty('Metric unavailable', 'The backend did not publish a value.')); return; }
          result.append(append(el('div', 'metric-result'), el('strong', 'metric-value', data.value == null ? 'Unavailable' : data.value.amount + ' ' + data.value.unit)));
          result.append(append(el('div', 'toolbar'), btn('Inputs, distribution & evidence', () => inspect('Metric details', 'CANONICAL METRIC', value), 'text-button'), btn('Download metric JSON', () => download(value, 'architecture-metric.json'), 'text-button')));
          const rankings = data.details?.ranking || data.details?.hotspots; if (Array.isArray(rankings)) {
            await resolveLabels(rankings.map(value => value.identity), env); if (env.signal.aborted || current !== version) return;
            result.append(el('h3', '', 'Ranked dependencies & coupling hotspots'), el('p', 'muted small', rankings.length + ' rows shown' + (data.details.rankingPopulation == null ? '' : ' from ' + data.details.rankingPopulation + ' ranked entities') + (data.details.rankingTruncated ? ' · Ranking is bounded' : '')));
            const maxDeg = Math.max(1, ...rankings.map(r => (Number(r.fanIn) || 0) + (Number(r.fanOut) || 0)));
            for (const value of rankings) {
              const rNode = row({ identity: value.identity, label: state.labels.get(value.identity) || M.shortDigest(value.identity), kind: 'Hotspot' }, async () => { const entity = await env.query('entity', { identity: value.identity }); if (!env.signal.aborted && entity.items[0]) return inspectEntity(entity.items[0], env); return inspect('Ranked metric entry', 'METRIC EVIDENCE', value); }, value.fanIn + ' incoming (fan-in) · ' + value.fanOut + ' outgoing (fan-out)');
              const barWrap = el('div', 'coupling-bars'), inBar = el('span', 'coupling-in'), outBar = el('span', 'coupling-out');
              inBar.style.width = Math.max(2, Math.round((Number(value.fanIn) || 0) * 100 / maxDeg)) + '%';
              outBar.style.width = Math.max(2, Math.round((Number(value.fanOut) || 0) * 100 / maxDeg)) + '%';
              barWrap.append(inBar, outBar);
              rNode.firstChild.append(barWrap);
              result.append(rNode);
            }
          }
        } catch (error) { if (!env.signal.aborted && current === version) result.replaceChildren(empty('Metric unavailable', error.message), btn('Retry', evaluate)); }
      }
      toolbar.append(btn('Read metric', evaluate, 'button primary'), btn('Use whole context', () => { selectedSubject = env.context; subjectLabel.textContent = 'Subject: the complete selected analysis context.'; return evaluate(); }));
      metric.addEventListener('change', () => safe(evaluate)); scope.addEventListener('change', () => safe(evaluate)); await evaluate(); return node;
    },
    async impact(env) {
      const node = card('Change impact (Blast Radius)', 'Find a class or component, then inspect the incoming dependency paths that may be affected if you modify it. This is potential structural impact, not a runtime simulation.');
      const form = el('form', 'toolbar'), input = el('input'), submit = el('button', 'button primary', 'Find symbol'); input.type = 'search'; input.placeholder = 'Class name or exact symbol identity (e.g. OrderService)'; input.setAttribute('aria-label', 'Symbol for impact analysis'); input.required = true; submit.type = 'submit'; form.append(input, submit); node.append(form);
      const results = empty('Choose a symbol', 'Search by class name, then select the exact symbol to calculate its upstream dependents and blast radius.'); node.append(results); let version = 0;
      form.addEventListener('submit', event => {
        event.preventDefault(); safe(async () => {
          const current = ++version; submit.disabled = true; results.replaceChildren(empty('Searching…', 'Reading matching symbols.'));
          try {
            const target = el('div'); await pagedList(target, env, cursor => env.query('entities', { search: input.value.trim() }, { size: 20, ...(cursor ? { cursor } : {}) }), item => row(item, async () => {
              const paths = await env.query('impact', { identity: item.identity }, undefined, { direction: 'INCOMING' }); if (!env.signal.aborted) return inspect(M.label(item), 'POTENTIAL STRUCTURAL IMPACT', paths);
            }), 'No matching symbols', 'Try a class name from the Structure view.'); if (current === version && !env.signal.aborted) results.replaceChildren(target);
          }
          catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Search unavailable', error.message)); }
          finally { submit.disabled = false; }
        });
      }); return node;
    },
    async graph(env) {
      const node = card('Dependency map');
      node.classList.add('dependency-map');
      let focus = state.graphFocus || {};
      const contexts = [...$('context').options].filter(option => option.value);
      const initialContext = focus.context && contexts.some(option => option.value === focus.context)
        ? focus.context : defaultDependencyContext(env);
      const context = selectControl('Graph context', contexts.map(option =>
        [option.value, option.value === env.snapshot ? 'Repository source evidence' : (state.contextLabels?.get(option.value) || option.textContent)]), initialContext);
      context.addEventListener('focus', labelContextOptions);
      context.addEventListener('pointerenter', labelContextOptions);
      const contextField = field('Graph context', context);
      contextField.hidden = env.context !== env.snapshot && initialContext === env.context;
      const scope = selectControl('Graph scope', [
        ['MODULE', 'Modules'], ['PACKAGE', 'Packages'], ['TYPE', 'Classes'],
        ['SPRING_COMPONENT', 'Spring components'], ['ENTITY', 'Source entities']
      ]);
      const projection = selectControl('Dependency view', [
        ['BOUNDED_UNION', 'All observed'], ['MUST', 'Required'], ['MAY', 'Conditional'], ['UNKNOWN_OVERLAY', 'Uncertain'],
        ...(focus.realization ? [['REALIZED', 'Recorded configuration']] : [])
      ], focus.projection || 'BOUNDED_UNION');
      const layout = selectControl('Graph layout', [
        ['breadthfirst', 'Hierarchy'], ['concentric', 'Radial'], ['circle', 'Ring'], ['grid', 'Grid']
      ], 'breadthfirst');
      const depth = selectControl('Neighborhood depth', [['1', '1 hop'], ['2', '2 hops'], ['3', '3 hops']]);
      const clear = btn('Show full map', () => {
        focus = { ...focus, seed: null }; depthField.hidden = clear.hidden = true;
        return load();
      });
      const depthField = field('Neighborhood', depth);
      depthField.hidden = clear.hidden = !focus.seed;
      const options = append(el('details', 'graph-options'), el('summary', '', 'View options'),
        append(el('div', 'graph-option-fields'), field('Relationships', projection), field('Layout', layout)));
      const content = el('div', 'graph-result');
      node.append(append(el('div', 'graph-query-controls'), contextField, field('Scope', scope), depthField, clear, options), content);
      const snapshotKinds = ['syntax.declaration', 'syntax.import', 'syntax.heritage', 'CALLS_SERVICE', 'INVOKES_RPC',
        'polyglot.service-call.candidate', 'polyglot.grpc-service-call.candidate', 'polyglot.grpc-contract.candidate'];
      const snapshotPopulation = {
        nodeKinds: ['SOURCE_DOCUMENT', 'SOURCE_DECLARATION', 'SOURCE_IMPORT', 'SOURCE_HERITAGE',
          'CALL_SITE', 'ROUTE_CANDIDATE', 'PROTOBUF_MESSAGE', 'PROTOBUF_SERVICE', 'PROTOBUF_RPC_METHOD']
      };
      let version = 0, canvasObserver, expandedDialog;
      function releaseCanvas() {
        canvasObserver?.disconnect();
        if (expandedDialog?.open) expandedDialog.close();
      }

      async function setScope() {
        const selected = context.value;
        if (selected !== env.snapshot) await labelContext(selected);
        void labelContextOptions();
        if (env.signal.aborted || context.value !== selected) return;
        const spring = state.contextKinds.get(selected) === 'spring.context';
        for (const option of scope.options) {
          option.disabled = selected === env.snapshot ? option.value !== 'ENTITY' : option.value === 'SPRING_COMPONENT' && !spring;
        }
        const label = state.contextLabels?.get(selected) || [...$('context').options].find(option => option.value === selected)?.textContent;
        if (label && selected !== env.snapshot && context.selectedOptions[0]) context.selectedOptions[0].textContent = label;
        const preferred = focus.scope ?? (selected === env.snapshot ? 'ENTITY' : spring ? 'SPRING_COMPONENT' : 'PACKAGE');
        scope.value = [...scope.options].some(option => !option.disabled && option.value === preferred) ? preferred : 'ENTITY';
      }

      function requestFor(selectedContext, layer) {
        const snapshot = selectedContext === env.snapshot;
        return {
          context: selectedContext, scope: layer, projection: projection.value,
          relationshipKinds: focus.relationshipKinds || (snapshot ? snapshotKinds : relationshipKinds(layer)),
          ...(snapshot && !focus.relationshipKinds ? { population: snapshotPopulation } : {}),
          ...(projection.value === 'REALIZED' ? { realization: focus.realization } : {})
        };
      }

      async function load(preferRelationships = false) {
        const current = ++version;
        const active = () => !env.signal.aborted && current === version;
        releaseCanvas();
        state.cy?.destroy();
        state.cy = null;
        refreshGraphTheme = null;
        content.replaceChildren(empty('Loading dependency map', 'Reading relationships from the selected analysis context.'));
        const selectedContext = context.value;
        let request = requestFor(selectedContext, scope.value);
        try {
          const queryGraph = query => env.query('visual-graph', focus.seed ? { seed: focus.seed, depth: Number(depth.value) } : {}, undefined, query);
          let graph;
          try {
            graph = await queryGraph(request);
          } catch (error) {
            if (!active() || !preferRelationships || request.scope !== 'PACKAGE' || error.code !== 'RESPONSE_TOO_LARGE') throw error;
            request = requestFor(selectedContext, 'MODULE');
            graph = await queryGraph(request);
          }
          // Keep a useful initial map for a single-package repository. Explicit filters stay put.
          if (active() && preferRelationships && request.scope === 'PACKAGE' && !graph.edges.length) {
            const typeRequest = requestFor(selectedContext, 'TYPE');
            // A more detailed projection may be larger than the API can return.
            const types = await queryGraph(typeRequest).catch(error => {
              if (graph.nodes.length && error.code === 'RESPONSE_TOO_LARGE') return null;
              throw error;
            });
            if (types && (types.edges.length || !graph.nodes.length && types.nodes.length)) {
              graph = types;
              request = typeRequest;
            }
          }
          if (!active()) return;
          scope.value = request.scope;
          const graphEnv = { ...env, context: selectedContext, request: { ...env.request, ...request } };
          if (!graph.nodes.length) {
            content.replaceChildren(empty(graph.metadata?.isTruncated ? 'The map reached its query limit' : 'No nodes in this scope',
              'Try another scope or browse the captured repository structure.'),
              btn('Browse structure', () => loadTab('structure')));
            return;
          }
          const rendererAvailable = await loadGraphRenderer();
          if (!active()) return;
          renderGraph(graph, graphEnv, request, active, rendererAvailable);
        } catch (error) {
          if (active()) content.replaceChildren(empty('Could not load the map', error.message), btn('Retry graph', () => load(preferRelationships)));
        }
      }

      function renderGraph(graph, graphEnv, request, active, rendererAvailable) {
        const color = name => getComputedStyle(node).getPropertyValue(name).trim();
        const compound = item => item.id.startsWith('compound:');
        const fullLabel = item => {
          const prefix = 'compound:module:';
          const module = item.id.startsWith(prefix) ? state.moduleLabels?.get(item.id.slice(prefix.length)) : null;
          return module === '.' ? 'Root module' : module || item.label || (item.kind === 'PACKAGE' ? '(default package)' : M.shortDigest(item.id));
        };
        const nodes = graph.nodes.filter(item => !compound(item));
        const byId = new Map(graph.nodes.map(item => [item.id, item]));
        const degree = new Map();
        for (const { source, target } of graph.edges) {
          degree.set(source, (degree.get(source) || 0) + 1);
          degree.set(target, (degree.get(target) || 0) + 1);
        }
        const ranked = [...nodes].sort((a, b) => (degree.get(b.id) || 0) - (degree.get(a.id) || 0) || a.id.localeCompare(b.id));
        const visible = ranked.slice(0, 220);
        const included = new Set(visible.map(item => item.id));
        for (const item of visible) {
          for (let parent = item.parent; byId.has(parent) && !included.has(parent); parent = byId.get(parent).parent) included.add(parent);
        }
        const edges = graph.edges.filter(edge => included.has(edge.source) && included.has(edge.target)).slice(0, 700);
        const limited = visible.length < nodes.length || edges.length < graph.edges.length;
        const counts = el('p', 'graph-counts',
          visible.length + ' nodes · ' + edges.length + ' relationships' +
          (limited ? ' shown of ' + nodes.length + ' nodes and ' + graph.edges.length + ' relationships returned' : ''));
        counts.setAttribute('role', 'status');
        const status = graph.metadata?.isTruncated ? el('p', 'graph-message', 'The query reached its limit. Focus a node to explore its neighborhood.') : null;
        const edgesMissing = !graph.edges.length ? el('p', 'graph-message', 'This scope has nodes and no recorded relationships for the selected view.') : null;
        const shortCounts = new Map();
        const shortName = item => fullLabel(item).split('.').at(-1);
        for (const item of visible) shortCounts.set(shortName(item), (shortCounts.get(shortName(item)) || 0) + 1);
        const label = item => {
          const text = fullLabel(item);
          if (compound(item) || !['TYPE', 'PACKAGE'].includes(item.kind)) return text;
          if (item.origin === 'PROJECT' && shortCounts.get(shortName(item)) === 1) return shortName(item);
          return text.split('.').slice(-2).join('.');
        };
        const inspectNode = item => inspectEntity({ ...item, label: fullLabel(item), identity: item.id }, graphEnv);
        const details = btn('Download graph JSON', () => download(graph, 'dependency-graph.json'));
        const nodeList = () => append(el('div', 'graph-node-list'), ...visible.slice(0, 24).map(item =>
          btn(fullLabel(item), () => inspectNode(item), 'graph-node-link')));
        content.replaceChildren(counts, ...[status, edgesMissing].filter(Boolean));
        if (!rendererAvailable) {
          content.append(empty('Interactive view unavailable', 'Inspect the captured nodes below or download the projection.'),
            nodeList(), details, btn('Retry graph', () => load()));
          return;
        }
        const canvas = el('div', 'graph-canvas');
        canvas.setAttribute('aria-label', 'Interactive dependency graph');
        canvas.tabIndex = 0;
        const selected = selectControl('Graph node', [['', 'Choose a node'], ...visible.map(item => [item.id, fullLabel(item)])], '');
        const filter = el('input');
        filter.type = 'search';
        filter.placeholder = 'Highlight a node';
        filter.setAttribute('aria-label', 'Filter canvas nodes');
        const readout = el('p', 'graph-selection', 'Select a node to see where its arrows lead.');
        readout.setAttribute('role', 'status');
        const spotlight = btn('Focus & expand', () => {
          focus = { ...focus, seed: selected.value }; depthField.hidden = clear.hidden = false;
          return load();
        });
        const inspectButton = btn('Node details', () => inspectNode(byId.get(selected.value)));
        const blastButton = btn('Blast radius', async () => {
          const id = selected.value;
          if (!id) return;
          const payload = M.buildImpactQuery
            ? M.buildImpactQuery(graphEnv.context, id, request)
            : { request: { ...env.request, ...request, direction: 'INCOMING' }, arguments: { identity: id } };
          const impact = await api('/graphs/' + env.graph + '/queries/impact', payload, env.signal);
          if (!active()) return;
          resetHighlight();
          const affected = new Set([id]);
          for (const entry of impact.items || []) {
            if (Array.isArray(entry.vertices)) entry.vertices.forEach(v => affected.add(v));
            else if (entry.target || entry.identity) affected.add(entry.target || entry.identity);
          }
          cy.elements().not(':parent').addClass('dimmed');
          cy.nodes().filter(n => affected.has(n.id())).removeClass('dimmed').addClass('path-highlight');
          cy.edges().filter(e => affected.has(e.source().id()) && affected.has(e.target().id())).removeClass('dimmed').addClass('path-highlight');
          const impactedCount = Math.max(0, affected.size - 1);
          readout.replaceChildren(el('strong', '', fullLabel(byId.get(id))),
            el('span', '', `${impactedCount} upstream impacted node${impactedCount === 1 ? '' : 's'} across ${impact.items?.length || 0} path${(impact.items?.length || 0) === 1 ? '' : 's'}`));
        });
        const askAiNode = btn('Ask AI', () => {
          const item = byId.get(selected.value);
          state.chatSeedPrompt = 'Explain the responsibilities, incoming/outgoing dependencies, and refactoring risk of `' + (item ? fullLabel(item) : selected.value) + '`.';
          return loadTab('architecture-chat');
        });
        spotlight.disabled = inspectButton.disabled = blastButton.disabled = askAiNode.disabled = true;
        const hubs = append(el('div', 'graph-node-list'), ...ranked.filter(item => degree.has(item.id)).slice(0, 4).map(item =>
          btn(fullLabel(item), () => selectNode(item.id), 'graph-node-link')));
        const rail = append(el('aside', 'graph-rail'), field('Node', selected), readout,
          append(el('div', 'graph-node-actions'), inspectButton, spotlight, blastButton, askAiNode),
          hubs.children.length ? append(el('div', 'graph-hubs'), el('h3', '', 'Most connected'), hubs) : null);
        const zoomBy = scale => cy.zoom({ level: cy.zoom() * scale, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
        const zoom = append(el('div', 'graph-zoom'),
          btn('−', () => zoomBy(.8)), btn('+', () => zoomBy(1.25)),
          btn('Fit', () => cy.fit(undefined, 34)));
        zoom.children[0].setAttribute('aria-label', 'Zoom out');
        zoom.children[1].setAttribute('aria-label', 'Zoom in');
        zoom.children[2].setAttribute('aria-label', 'Fit graph');
        const filterChips = el('div', 'graph-filter-chips');
        const activeFilter = { kind: 'ALL' };
        const applyChipFilter = kind => {
          activeFilter.kind = kind;
          for (const ch of filterChips.children) ch.classList.toggle('is-active', ch.dataset.filter === kind);
          if (!cy) return;
          resetHighlight();
          if (kind === 'ALL') return;
          let matched;
          if (kind === 'EXTERNAL') matched = cy.nodes().filter(n => n.data('external'));
          else matched = cy.edges().filter(e => e.data('classification') === kind).connectedNodes().union(cy.edges().filter(e => e.data('classification') === kind));
          cy.elements().not(':parent').not(matched).addClass('dimmed');
          matched.addClass('map-active');
        };
        for (const [fk, fl] of [['ALL', 'All'], ['MUST', 'Required'], ['MAY', 'Conditional'], ['UNKNOWN', 'Uncertain'], ['EXTERNAL', 'External']]) {
          const ch = btn(fl, () => applyChipFilter(fk), 'graph-chip' + (fk === 'ALL' ? ' is-active' : ''));
          ch.dataset.filter = fk;
          filterChips.append(ch);
        }
        const dialog = el('dialog', 'map-fullscreen');
        dialog.setAttribute('aria-label', 'Expanded dependency map');
        const expand = btn('Expand map', () => {
          previousViewport = { zoom: cy.zoom(), pan: cy.pan() };
          dialog.append(board);
          dialog.showModal();
          cy.resize();
          cy.fit(undefined, 34);
        }, 'button graph-fullscreen-button');
        expand.title = 'Expand map (F)';
        zoom.append(expand);
        const stage = append(el('div', 'graph-stage'), filterChips, append(el('div', 'graph-stage-controls'), filter, zoom), canvas,
          append(el('div', 'map-legend'),
            el('span', 'legend-must', 'Required'), el('span', 'legend-may', 'Conditional'), el('span', 'legend-unknown', 'Uncertain'),
            visible.some(item => item.origin && item.origin !== 'PROJECT') ? el('span', 'legend-external', 'External') : null));
        const board = append(el('div', 'graph-board'), stage, rail);
        dialog.append(append(el('header', 'map-fullscreen-header'), el('h3', '', 'Dependency map'), btn('Close map', () => dialog.close())));
        content.append(board, dialog);
        let cy, previousViewport;
        const graphStyle = () => [
          {
            selector: 'node', style: {
              label: 'data(label)', 'font-size': 12, 'font-weight': 600, color: color('--map-label'),
              shape: 'round-rectangle', 'background-color': color('--map-node'), 'border-color': color('--map-node-border'), 'border-width': 1.2,
              width: 'data(nodeWidth)', height: 34, 'text-valign': 'center', 'text-halign': 'center', 'text-wrap': 'ellipsis', 'text-max-width': 115
            }
          },
          {
            selector: ':parent', style: {
              'background-color': color('--map-group'), 'background-opacity': .22, 'border-width': 1,
              'border-color': color('--map-node-border'), padding: 20, 'text-valign': 'top', 'text-halign': 'center', 'text-max-width': 165
            }
          },
          { selector: 'node[?external]', style: { 'background-color': color('--map-external'), 'border-color': color('--map-edge') } },
          {
            selector: 'edge', style: {
              'curve-style': 'bezier', 'target-arrow-shape': 'triangle', 'line-color': color('--map-edge'),
              'target-arrow-color': color('--map-edge'), width: 1.5, opacity: .8
            }
          },
          { selector: 'edge[classification="MAY"]', style: { 'line-style': 'dashed', 'line-color': color('--map-may'), 'target-arrow-color': color('--map-may') } },
          { selector: 'edge[classification="UNKNOWN"]', style: { 'line-style': 'dotted', 'line-color': color('--map-unknown'), 'target-arrow-color': color('--map-unknown') } },
          { selector: 'node[activationStatus="UNKNOWN"]', style: { 'border-style': 'dashed', 'border-color': color('--map-unknown') } },
          { selector: '.dimmed', style: { opacity: .13 } },
          { selector: 'node.map-active', style: { 'background-color': color('--map-focus'), 'border-width': 2.5 } },
          { selector: 'edge.map-active', style: { width: 2.3, opacity: 1 } },
          { selector: '.path-highlight', style: { 'border-width': 3, 'border-color': color('--map-unknown'), 'line-color': color('--map-unknown'), 'target-arrow-color': color('--map-unknown'), width: 3 } }
        ];
        try {
          cy = window.cytoscape({
            container: canvas, elements: [
              ...graph.nodes.filter(item => included.has(item.id)).map(item => {
                const deg = degree.get(item.id) || 0;
                return {
                  data: {
                    ...item, label: label(item), fullLabel: fullLabel(item),
                    nodeWidth: Math.min(152, 88 + deg * 4),
                    parent: included.has(item.parent) ? item.parent : undefined, activationStatus: item.properties?.activationStatus,
                    external: Boolean(item.origin && item.origin !== 'PROJECT')
                  }
                };
              }),
              ...edges.map(item => ({ data: item }))
            ], layout: layoutConfig(layout.value), minZoom: .06, maxZoom: 3, wheelSensitivity: .17, style: graphStyle()
          });
        } catch (error) {
          stage.replaceChildren(empty('Could not draw the map', error.message), nodeList(), btn('Retry graph', () => load()));
          rail.replaceChildren(details);
          return;
        }
        state.cy = cy;
        expandedDialog = dialog;
        dialog.addEventListener('close', () => {
          if (!active()) return;
          dialog.before(board);
          cy.resize();
          cy.viewport(previousViewport);
          expand.focus({ preventScroll: true });
        });
        refreshGraphTheme = () => cy.style(graphStyle()).update();
        graph.nodes.forEach(item => state.labels.set(item.id, fullLabel(item)));
        canvasObserver = new ResizeObserver(() => { if (active() && !cy.destroyed()) cy.resize(); });
        canvasObserver.observe(canvas);

        function resetHighlight() { cy.elements().removeClass('dimmed map-active path-highlight'); }
        function selectNode(id) {
          const item = byId.get(id), picked = cy.getElementById(id);
          if (!item || compound(item) || !picked.length) return;
          selected.value = id;
          spotlight.disabled = inspectButton.disabled = blastButton.disabled = askAiNode.disabled = false;
          filter.value = '';
          resetHighlight();
          const neighborhood = picked.closedNeighborhood();
          cy.elements().not(neighborhood).not(':parent').addClass('dimmed');
          neighborhood.addClass('map-active');
          readout.replaceChildren(el('strong', '', fullLabel(item)),
            el('span', '', picked.indegree(false) + ' incoming · ' + picked.outdegree(false) + ' outgoing on this map'));
        }
        selected.addEventListener('change', () => {
          if (selected.value) { selectNode(selected.value); return; }
          resetHighlight();
          spotlight.disabled = inspectButton.disabled = blastButton.disabled = askAiNode.disabled = true;
          readout.textContent = 'Select a node to see where its arrows lead.';
        });
        filter.addEventListener('input', () => {
          resetHighlight();
          const term = filter.value.trim().toLowerCase();
          if (!term) return;
          const matches = cy.nodes().not(':parent').filter(item => String(item.data('fullLabel')).toLowerCase().includes(term));
          cy.elements().not(matches.union(matches.neighborhood())).not(':parent').addClass('dimmed');
          matches.addClass('map-active');
        });
        cy.on('tap', 'node', event => selectNode(event.target.id()));
        cy.on('tap', 'edge', event => safe(() => {
          const item = event.target.data();
          const actions = (item.properties?.evidence ?? []).slice(0, 8).map(reference => ({
            label: 'Open source evidence',
            run: () => inspect('Dependency source', 'SOURCE EVIDENCE', signal => api('/graphs/' + env.graph + '/queries/evidence', { request: graphEnv.request, arguments: { reference } }, signal))
          }));
          return inspect('Dependency evidence', 'DEPENDENCY', item, actions);
        }));
        canvas.addEventListener('keydown', event => {
          if (!['+', '=', '-', '0'].includes(event.key)) return;
          event.preventDefault();
          if (event.key === '0') cy.fit(undefined, 34);
          else zoomBy(event.key === '-' ? .8 : 1.25);
        });
        const destination = selectControl('Path target', visible.map(item => [item.id, fullLabel(item)]));
        const pathStatus = el('p', 'graph-message');
        rail.append(append(el('details', 'graph-options'), el('summary', '', 'Paths and export'),
          field('Path target', destination), btn('Find shortest path', async () => {
            if (!selected.value) { pathStatus.textContent = 'Select a start node first.'; return; }
            const result = await env.query('shortest-path', { source: selected.value, target: destination.value }, undefined, request);
            if (!active()) return;
            const path = result.items[0];
            resetHighlight();
            if (path) {
              cy.elements().not(':parent').addClass('dimmed');
              cy.nodes().filter(n => path.vertices.includes(n.id())).removeClass('dimmed').addClass('path-highlight');
              cy.edges().filter(e => path.edges.some(p => p.source === e.source().id() && p.target === e.target().id())).removeClass('dimmed').addClass('path-highlight');
            }
            pathStatus.textContent = path ? 'Path highlighted where visible on the map.' : 'No path returned for this query.';
            await inspect('Shortest dependency path', 'PATH EVIDENCE', result);
          }), pathStatus, details, btn('Save graph image', async () => {
            const blob = await cy.png({ output: 'blob-promise', full: true, scale: 1, maxWidth: 3000, maxHeight: 3000, bg: color('--map-paper') });
            downloadBlob(blob, 'dependency-graph.png');
          })));
        if (focus.seed && included.has(focus.seed)) selectNode(focus.seed);
      }

      function resetFocus() { focus = {}; depthField.hidden = clear.hidden = true; }
      function resetRealization() {
        for (const option of projection.options) if (option.value === 'REALIZED') option.disabled = true;
        if (projection.value === 'REALIZED') projection.value = 'BOUNDED_UNION';
      }
      context.addEventListener('change', () => safe(async () => {
        const selected = context.value;
        resetFocus(); resetRealization();
        await setScope();
        if (!env.signal.aborted && context.value === selected) await load(true);
      }));
      scope.addEventListener('change', () => safe(() => { resetFocus(); resetRealization(); return load(); }));
      projection.addEventListener('change', () => safe(() => load()));
      depth.addEventListener('change', () => safe(() => load()));
      layout.addEventListener('change', () => state.cy?.layout(layoutConfig(layout.value)).run());
      env.signal.addEventListener('abort', releaseCanvas, { once: true });
      await setScope();
      return { node, after: () => safe(() => load(!focus.scope)) };
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
    async connections(env) {
      const node = card('Cross-language connections', 'Trace links between code and contracts. Candidate links still need evidence to confirm their targets.');
      const kinds = {
        CALLS_SERVICE: 'Service connection',
        INVOKES_RPC: 'RPC connection',
        PUBLISHES_EVENT: 'Event flow',
        'polyglot.service-call.candidate': 'HTTP route candidate',
        'polyglot.event-flow.candidate': 'Event topic candidate',
        'polyglot.grpc-service-call.candidate': 'gRPC route candidate',
        'polyglot.grpc-contract.candidate': 'gRPC contract candidate',
        'polyglot.grpc-implements.candidate': 'gRPC implementation candidate'
      };
      const overrides = { context: env.snapshot || env.context, scope: 'ENTITY', relationshipKinds: Object.keys(kinds) };
      node.append(append(el('div', 'toolbar'),
        btn('Back to dependency graph', () => loadTab('graph'), 'text-button'),
        state.capabilities?.bridgesEnabled ? Object.assign(el('a', 'button', 'Open captured connections'), { href: '#bridge-evidence' }) : null));
      const entries = append(el('div', 'connections-list'));
      node.append(entries);
      await pagedList(entries, env,
        cursor => env.query('relationships', {}, { size: 12, ...(cursor ? { cursor } : {}) }, overrides), item => {
          const candidate = item.kind.endsWith('.candidate') || item.targetState !== 'RESOLVED';
          return append(btn('', () => inspect(kinds[item.kind], candidate ? 'CANDIDATE CONNECTION' : 'CAPTURED CONNECTION',
            { kind: item.kind, targetState: item.targetState, source: item.source, targets: item.targets, evidence: item.evidence },
            (item.evidence ?? []).slice(0, 8).map((reference, index) => ({
              label: 'Open evidence ' + (index + 1),
              run: () => inspect('Connection evidence', 'SOURCE EVIDENCE', signal =>
                api('/graphs/' + env.graph + '/queries/evidence',
                  { request: { ...env.request, ...overrides }, arguments: { reference } }, signal))
            }))), 'entity-row'), append(el('div'), el('strong', '', kinds[item.kind]),
              el('p', '', item.targets.length + (item.targets.length === 1 ? ' recorded target' : ' recorded targets'))),
            badge(candidate ? 'Candidate' : 'Recorded'));
        }, 'No connections in this capture', 'The available analysis has no cross-language connection records for this snapshot.');
      return node;
    },
    insights(env) {
      const root = el('div', 'insights-view');
      const context = defaultDependencyContext(env);
      const scope = selectControl('Insights scope', env.springContext
        ? [['SPRING_COMPONENT', 'Spring components']]
        : [['TYPE', 'Types'], ['PACKAGE', 'Packages'], ['MODULE', 'Modules']]);
      const lastScope = state.insightsScope;
      if ([...scope.options].some(option => option.value === lastScope)) scope.value = lastScope;
      const exports = el('div', 'toolbar-actions');
      const status = el('p', 'small muted');
      status.setAttribute('role', 'status');
      const body = el('div', 'insights-results');
      root.append(append(el('div', 'toolbar'), field('Scope', scope), exports), status, body);
      let pending;

      const openGraph = (seed, sc) => {
        state.graphFocus = { seed, scope: sc, context, relationshipKinds: relationshipKinds(sc) };
        return loadTab('graph');
      };
      function table(headers, rows) {
        const header = append(el('tr'), ...headers.map(label => Object.assign(el('th', '', label), { scope: 'col' })));
        return append(el('div', 'table-scroll'), append(el('table', 'comparison-table insights-table'),
          append(el('thead'), header), append(el('tbody'), ...rows)));
      }

      async function loadInsights() {
        pending?.abort();
        const controller = new AbortController();
        pending = controller;
        const signal = AbortSignal.any([env.signal, controller.signal]);
        const sc = scope.value;
        state.insightsScope = sc;
        status.textContent = 'Calculating structural insights…';
        body.setAttribute('aria-busy', 'true');
        body.replaceChildren();
        exports.replaceChildren();
        try {
          const request = { ...env.request, context, scope: sc, relationshipKinds: relationshipKinds(sc) };
          const data = await api('/graphs/' + env.graph + '/architecture-insights', { request, arguments: { topN: 10 } }, signal);
          if (signal.aborted) return;
          status.textContent = '';
          exports.append(btn('Download report', () => {
            downloadBlob(new Blob([data.markdownReport], { type: 'text/markdown;charset=utf-8' }), 'architecture-insights.md');
          }));
          const summary = data.summary;
          const stats = el('div', 'stats');
          const statRows = [
            ['Entities', summary.analyzedEntities], ['Dependencies', summary.internalDependencies],
            ['Cyclic groups', summary.tangleCount], ['Max. transitive dependents', summary.maxBlastRadius]
          ];
          if (summary.abstractness != null) statRows.push(['Abstractness (A)', Number(summary.abstractness).toFixed(2)]);
          if (summary.distanceFromMainSequence != null) statRows.push(['Main-Sequence Dist. (D)', Number(summary.distanceFromMainSequence).toFixed(2)]);
          if (summary.edgeSdpViolationCount != null) statRows.push(['SDP Violations', summary.edgeSdpViolationCount]);
          for (const [label, value] of statRows) stats.append(append(el('div', 'stat'), el('span', '', label), el('strong', '', value)));
          body.append(stats);

          if (Array.isArray(data.sdpViolations) && data.sdpViolations.length) {
            const sdpCard = card('Stable Dependencies Principle (SDP) Violations', 'Edges pointing from a more stable package/component toward a less stable one (Δ instability > 0).');
            sdpCard.append(table(['Stable Source (I)', 'Less Stable Target (I)', 'Delta (ΔI)', 'Inspect'], data.sdpViolations.slice(0, 25).map(v => append(el('tr'),
              el('td', '', `${v.sourceLabel || v.source} (${Number(v.sourceInstability ?? 0).toFixed(2)})`),
              el('td', '', `${v.targetLabel || v.target} (${Number(v.targetInstability ?? 0).toFixed(2)})`),
              el('td', '', '+' + Number(v.delta ?? 0).toFixed(2)),
              append(el('td'), btn('Open in graph', () => openGraph(v.source, sc), 'text-button'))))));
            body.append(sdpCard);
          }

          if (Array.isArray(data.packageMetrics) && data.packageMetrics.length) {
            const pkgCard = card('Package Stability & Main-Sequence Distance', 'Robert C. Martin metrics: Abstractness (A), Instability (I = Ce / (Ca + Ce)), and Distance from Main Sequence (D = |A + I - 1|).');
            pkgCard.append(table(['Package / Group', 'Entities', 'A', 'I', 'D', 'Zone'], data.packageMetrics.slice(0, 30).map(pm => {
              const zoneRaw = String(pm.zone || 'BALANCED');
              const zoneCls = /PAIN/i.test(zoneRaw) ? 'is-pain' : /USELESS/i.test(zoneRaw) ? 'is-useless' : 'is-main';
              return append(el('tr'),
                el('td', '', pm.group),
                el('td', '', `${pm.entityCount ?? 0} (${pm.abstractCount ?? 0} abs)`),
                el('td', '', Number(pm.abstractness ?? 0).toFixed(2)),
                el('td', '', Number(pm.instability ?? 0).toFixed(2)),
                el('td', '', Number(pm.distance ?? 0).toFixed(2)),
                append(el('td'), el('span', 'zone-badge ' + zoneCls, zoneRaw.replaceAll('_', ' '))));
            })));
            body.append(pkgCard);
          }

          const cycles = card('Dependency cycles', 'Cycles in the observed graph can include dependencies from different configurations.');
          if (!data.tangles.length) cycles.append(el('p', 'small muted', 'No cycles observed in this projection.'));
          for (const tangle of data.tangles) {
            const item = append(el('details', 'insights-cycle'),
              el('summary', '', tangle.memberCount + ' entities · ' + tangle.internalEdgeCount + ' internal dependencies'),
              el('p', 'small', tangle.memberLabels.slice(0, 10).join(', ') + (tangle.memberLabels.length > 10 ? ' …' : '')),
              append(el('div', 'toolbar'),
                btn('Explore group in graph', () => openGraph(tangle.members[0], sc)),
                state.capabilities?.architectureChatEnabled ? btn('Ask AI to decouple', () => {
                  state.chatSeedPrompt = 'How should we safely decouple the dependency cycle among ' + tangle.memberLabels.slice(0, 8).join(', ') + '?';
                  return loadTab('architecture-chat');
                }, 'button subtle') : null));
            for (const cut of tangle.breakCandidates) {
              item.append(append(el('div', 'insights-cut'), el('strong', '', cut.sourceLabel + ' → ' + cut.targetLabel),
                el('p', 'small muted', cut.rationale),
                btn('Inspect edge evidence', () => inspect('Projected edge evidence', 'STRUCTURAL PROJECTION',
                  { edge: cut.edgeIdentity, evidence: cut.evidence, classification: cut.classification, supports: cut.supportCount }), 'text-button')));
            }
            cycles.append(item);
          }
          body.append(cycles);

          const hotspots = card('Highest-impact entities', 'Prioritized using observed coupling, cycle membership and available policy findings.');
          if (data.hotspots.length) {
            const maxRisk = Math.max(1, ...data.hotspots.map(h => h.hotspotScore || 0));
            hotspots.append(table(['Entity', 'Archetype', 'Risk score', 'Dependents', 'Direct in / out'], data.hotspots.map(h => {
              const fill = el('div', 'hotspot-risk-fill');
              fill.style.width = Math.min(100, Math.round(((h.hotspotScore || 0) / maxRisk) * 100)) + '%';
              const riskCell = append(el('div', 'hotspot-risk-wrap'), el('span', '', h.hotspotScore), append(el('div', 'hotspot-risk-bar'), fill));
              return append(el('tr'),
                append(el('td'), btn(h.label, () => openGraph(h.identity, sc), 'text-button')),
                el('td', '', h.archetype || '—'),
                append(el('td'), riskCell),
                el('td', '', h.transitiveDependents),
                el('td', '', h.fanIn + ' / ' + h.fanOut));
            })));
          } else hotspots.append(el('p', 'small muted', 'No project entities in this projection.'));
          body.append(hotspots);

          const cells = data.couplingMatrix.cells;
          const orderedGroups = data.couplingMatrix.orderedGroups || [];
          if (cells.length) {
            const coupling = card('Cross-group coupling', 'Showing ' + Math.min(cells.length, 20) + ' of ' + cells.length + ' group pairs by evidence weight.');
            if (orderedGroups.length >= 2 && orderedGroups.length <= 16) {
              const cellMap = new Map(cells.map(c => [`${c.fromGroup}\0${c.toGroup}`, c]));
              const maxWeight = Math.max(1, ...cells.map(c => c.edgeCount || 0));
              const dsm = el('table', 'dsm-matrix');
              const headRow = el('tr');
              headRow.append(el('th', '', 'From \\ To'));
              orderedGroups.forEach((g, idx) => { const th = el('th', '', String(idx + 1)); th.title = g; headRow.append(th); });
              const dsmBody = el('tbody');
              orderedGroups.forEach((fromG, r) => {
                const tr = el('tr');
                const rowHead = el('th', '', `${r + 1}. ${fromG.split('.').at(-1)}`);
                rowHead.title = fromG;
                tr.append(rowHead);
                orderedGroups.forEach((toG, c) => {
                  if (r === c) { tr.append(el('td', 'dsm-cell is-self', '·')); return; }
                  const hit = cellMap.get(`${fromG}\0${toG}`);
                  const td = el('td', 'dsm-cell' + (hit?.backEdge ? ' is-backedge' : ''), hit ? String(hit.edgeCount) : '');
                  if (hit) {
                    const alpha = (0.14 + 0.56 * Math.min(1, hit.edgeCount / maxWeight)).toFixed(2);
                    td.style.background = `color-mix(in srgb, var(--wb-accent) ${Math.round(alpha * 100)}%, transparent)`;
                    td.title = `${fromG} → ${toG}: ${hit.edgeCount} deps (weight ${hit.supportWeight})`;
                  }
                  tr.append(td);
                });
                dsmBody.append(tr);
              });
              dsm.append(append(el('thead'), headRow), dsmBody);
              coupling.append(append(el('div', 'dsm-matrix-scroll'), dsm));
            }
            coupling.append(table(['From', 'To', 'Dependencies', 'Evidence weight'], cells.slice(0, 20).map(cell => append(el('tr'),
              el('td', '', cell.fromGroup), el('td', '', cell.toGroup), el('td', '', cell.edgeCount), el('td', '', cell.supportWeight)))));
            body.append(coupling);
          }
        } catch (error) {
          if (signal.aborted) return;
          status.textContent = 'Insights unavailable.';
          body.replaceChildren(el('p', '', error.message), btn('Retry', loadInsights));
        } finally {
          if (!signal.aborted) body.setAttribute('aria-busy', 'false');
        }
      }

      scope.addEventListener('change', loadInsights);
      return { node: root, after: loadInsights };
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
  document.querySelectorAll('[data-action=connect]').forEach(n => n.addEventListener('click', () => { $('connect-title').textContent = 'Connect your code'; $('connect-error').hidden = true; $('connect-dialog').showModal(); }));
  $('policy-example').addEventListener('click', () => { if ($('repository-policy').value.trim()) { $('connect-error').textContent = 'The policy field already contains rules. Clear it before inserting an example.'; $('connect-error').hidden = false; return; } $('repository-policy').value = 'schema: architecture-policy:v1\nid: project.cycles\nversion: "1"\nrules:\n  - id: project.no-cycles\n    version: "1"\n    kind: ACYCLIC\n    severity: ERROR\n    scope: TYPE\n    relationships: [graph.depends-on]\n    within: {origins: [PROJECT]}\n'; });
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
  $('analysis-mode').addEventListener('change', () => { state.retry = null; syncRunControls(); });
  $('job-timing').addEventListener('toggle', () => { if ($('job-timing').open) loadTelemetry(); });
  $('context').addEventListener('focus', labelContextOptions);
  $('context').addEventListener('pointerenter', labelContextOptions);
  $('analyze').addEventListener('click', () => safe(() => submit()));
  $('analyze-force').addEventListener('click', () => safe(() => submit(true)));
  $('cancel-job').addEventListener('click', () => safe(async () => {
    const id = state.job?.id, generation = state.pollGeneration; if (!id) return;
    const job = await api('/analysis/jobs/' + id + '/cancel', {});
    if (selectedJob(id, generation) && job.revision >= state.job.revision) { state.job = job; renderJob(job); }
  }));
  $('history-more').addEventListener('click', () => safe(() => refreshHistory(true)));
  $('context').addEventListener('change', () => { state.graphFocus = null; state.explorer = null; state.metricSubject = null; state.context = $('context').value; safe(() => loadTab(state.tab)); });
  $('tabs').addEventListener('click', event => { const tab = event.target.closest('[data-tab]'); if (tab) safe(() => loadTab(tab.dataset.tab)); });
  $('tabs').addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    const tabs = [...$('tabs').querySelectorAll('[role=tab]:not([hidden])')];
    const index = tabs.indexOf(event.target);
    if (index < 0) return;
    event.preventDefault();
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
      : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
    tabs.forEach((tab, i) => { tab.tabIndex = i === next ? 0 : -1; });
    tabs[next].focus();
  });
  $('search-form').addEventListener('submit', event => { event.preventDefault(); safe(() => loadTab('structure')); });
  window.addEventListener('keydown', event => {
    if ($('repository-workspace').hidden) return;
    const tag = document.activeElement?.tagName;
    const inInput = ['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) || document.activeElement?.isContentEditable;
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      if (!$('connect-dialog').open && !$('inspector').open) {
        event.preventDefault();
        window.EvoCommandPalette?.open?.();
      }
      return;
    }
    if (event.key === '/' && !inInput && !event.ctrlKey && !event.metaKey) {
      if (!$('analysis-workspace').hidden && !$('connect-dialog').open && !$('inspector').open) {
        event.preventDefault();
        if (state.tab !== 'structure') safe(() => loadTab('structure'));
        $('search').focus();
        $('search').select();
      }
    }
  });
  $('download-details').addEventListener('click', () => download(state.details, 'evo-evidence.json'));
  $('disconnect-repo').addEventListener('click', () => {
    $('run-options').open = false;
    promptDisconnect(state.setup);
  });
  document.querySelectorAll('.view-links [data-open-tab]').forEach(button =>
    button.addEventListener('click', () => safe(() => loadTab(button.dataset.openTab))));
  document.addEventListener('click', event => {
    for (const menu of document.querySelectorAll('.workbench-menu[open]')) {
      if (!menu.contains(event.target)) menu.open = false;
    }
  });
  document.addEventListener('keydown', event => {
    const menu = event.target.closest?.('.workbench-menu[open]');
    if (event.key === 'Escape' && menu) { menu.open = false; menu.querySelector('summary').focus(); }
  });
  function applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    refreshGraphTheme?.();
    const label = 'Switch to ' + (theme === 'dark' ? 'light' : 'dark') + ' theme';
    const paths = theme === 'dark' ? '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>' : '<path d="M20 15.5A8 8 0 0 1 8.5 4 8 8 0 1 0 20 15.5Z"/>';
    $('theme-toggle').innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' + paths + '</svg>';
    $('theme-toggle').className = 'icon-button';
    $('theme-toggle').setAttribute('aria-label', label);
    $('theme-toggle').setAttribute('data-tooltip', '');
    try { localStorage.setItem('evo_theme', theme); } catch (_) { }
  }
  let theme = 'dark'; try { theme = localStorage.getItem('evo_theme') === 'light' ? 'light' : 'dark'; } catch (_) { } applyTheme(theme);
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
    if (mobile && expanded) $('sidebar').querySelector('button:not(:disabled), a[href]')?.focus();
    state.cy?.resize();
  });
  $('sidebar').addEventListener('keydown', event => { if (event.key === 'Escape') closeMobileSidebar(); });
  document.addEventListener('focusin', event => {
    if (!$('sidebar').contains(event.target) && event.target !== $('sidebar-toggle') && !event.target.closest('dialog')) closeMobileSidebar();
  });
  function syncSidebarState() {
    const shell = document.querySelector('.app-shell');
    const expanded = matchMedia('(max-width:760px)').matches ? shell.classList.contains('mobile-sidebar-open')
      : !shell.classList.contains('sidebar-collapsed');
    $('sidebar-toggle').setAttribute('aria-expanded', String(expanded));
    $('sidebar').inert = !expanded;
  }
  window.addEventListener('resize', () => { state.cy?.resize(); syncSidebarState(); });
  syncSidebarState();
  window.EvoWorkbench = {
    async searchSymbols(query) {
      if (!state.graph || !state.context) return [];
      const res = await api('/graphs/' + state.graph + '/queries/entities', {
        request: { ...M.request(state.context), scope: 'TYPE' },
        arguments: { search: query },
        page: { size: 8 }
      });
      return (res?.items || []).map(item => ({
        ...item,
        label: M.label(item),
        group: item.module && !String(item.module).includes('sha256:') ? item.module : (item.origin || 'Symbol')
      }));
    },
    focusSymbol(sym) {
      if (!sym?.identity) return;
      state.graphFocus = { seed: sym.identity, scope: 'TYPE', context: state.context, relationshipKinds: relationshipKinds('TYPE') };
      return loadTab('graph');
    },
    openCapturedSource(path, startLine = 1, endLine = 200, highlightLine = null) {
      if (!state.graph || !path) return;
      return openCapturedSourceModal(state.graph, path, startLine, endLine, highlightLine);
    },
    askAiPrompt(prompt) {
      state.chatSeedPrompt = prompt || '';
      return loadTab('architecture-chat');
    }
  };
  window.EvoApp = { state, api, loadTab, selectRepo, selectJob, configureRules, inspectFinding, inspectDiagnosis, inspectRepairProposal, inspectRepairVerification, selectRemediationFinding };
  $('disconnect-dialog').addEventListener('close', () => $('run-options').querySelector('summary').focus());
  window.addEventListener('hashchange', updateWorkspaceTool);
  safe(connectServer);
})();
