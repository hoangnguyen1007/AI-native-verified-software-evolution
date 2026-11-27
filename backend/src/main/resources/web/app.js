/* API-backed presentation only. No synthetic repositories, findings or scores. */
(() => {
  'use strict';
  const M = window.EvoModel, $ = id => document.getElementById(id);
  const state = { setups: [], connections: [], jobs: [], labels: new Map(), setup: null, job: null, resultJob: null, graph: null, contexts: [], reports: [], context: '', tab: 'overview', epoch: 0, pollGeneration: 0, historyLimit: 8, cy: null, online: false, retry: null, submitting: null, autoOpenLog: false, observedJobStates: new Map(), eventSource: null, liveProgress: null, truthTier: 'NONE', previewMeta: null, promotedFromPreview: false, incrementalSummary: null, scoreV2Cache: new Map(), pendingGraphViewport: null };
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
  let refreshGraphTheme;
  const setRefreshGraphTheme = fn => { refreshGraphTheme = typeof fn === 'function' ? fn : null; };
  function emitSnapshotChanged() {
    window.EvoBus?.emit?.('evo:snapshot-changed', {
      graphId: state.graph,
      snapshotId: state.snapshot,
      contextId: state.context,
      truthTier: state.truthTier,
      contexts: state.contexts || []
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
    const parsedHash = M.parseToolHash ? M.parseToolHash(location.hash) : { baseHash: location.hash, params: {} };
    const selected = workspaceTools[parsedHash.baseHash], panel = $('workbench-tool'), content = $('workbench-tool-content');
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
    } else if (parsedHash.baseHash === '#compare' && parsedHash.params && Object.keys(parsedHash.params).length) {
      tool.EvoApplyCompareParams?.({
        left: parsedHash.params.left || '',
        right: parsedHash.params.right || '',
        repo: parsedHash.params.repo || '',
        autorun: parsedHash.params.autorun === '1' || parsedHash.params.autorun === 'true'
      });
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
        state.graphFocus = null; state.explorer = null; state.metricSubject = null;
        state.context = next.identity;
        $('context').value = next.identity;
        notifyContextsUpdated();
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
  async function api(path, body, signal, method = (body === undefined ? 'GET' : 'POST'), timeoutMillis = 180000, _csrfRetried = false) {
    const timeout = AbortSignal.timeout(timeoutMillis), combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
    const headers = { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) };
    if (method !== 'GET' && method !== 'HEAD') {
      const csrf = window.EvoGitHubAuth?.getCsrfToken?.();
      if (csrf) headers['X-Evo-CSRF'] = csrf;
    }
    let response;
    try { response = await fetch((path.startsWith('/api/') ? '' : '/api/v1') + path, { method, credentials: 'same-origin', headers, ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: combined }); }
    catch (error) { if (signal?.aborted) throw error; throw new Error(error.name === 'TimeoutError' ? 'The server took too long. Retry or narrow the request.' : 'Cannot reach the analysis server. Check that it is running, then refresh.'); }
    let envelope = {};
    try {
      const text = await response.text();
      envelope = text ? JSON.parse(text) : {};
    } catch (_) {
      envelope = {};
    }
    if (response.status === 403 && envelope.error?.code === 'CSRF_TOKEN_INVALID' && !_csrfRetried && window.EvoGitHubAuth?.refreshAuth) {
      await window.EvoGitHubAuth.refreshAuth({ notifyOnChange: false }).catch(() => {});
      return api(path, body, signal, method, timeoutMillis, true);
    }
    if (!response.ok || envelope.error) {
      throw Object.assign(new Error(envelope.error?.message || M.apiError(envelope.error?.code) || `Server error (${response.status}).`),
        { code: envelope.error?.code, status: response.status, supersededBy: envelope.error?.supersededBy || envelope.supersededBy || null });
    }
    return envelope.data;
  }
  const repoLabel = id => {
    const conn = state.connections.find(c => c.setup === id);
    const setup = state.setups.find(s => s.id === id);
    return cleanRepoName(id, conn, setup);
  };
  function closeJobStream() {
    if (state.eventSource) {
      try { state.eventSource.close(); } catch (_) { }
      state.eventSource = null;
    }
  }
  function invalidate(keepInspector = false) { state.epoch++; state.view?.abort(); state.cy?.destroy(); state.cy = null; refreshGraphTheme = null; if (!keepInspector) closeInspector(); $('view-status').textContent = ''; }
  function clearReadiness() {
    state.readiness?.abort(); state.readiness = null;
    state.readinessStage = '';
    state.buildPreview = null;
  }
  function clearLiveTelemetryUi() {
    state.liveProgress = null;
    state.incrementalSummary = null;
    for (const id of ['job-live-metrics', 'job-live-inline', 'job-module-pills', 'job-incremental-strip']) {
      const node = $(id);
      if (node) { node.hidden = true; node.replaceChildren(); }
    }
    const cmp = $('job-compare-prev');
    if (cmp) cmp.hidden = true;
  }
  function renderTruthBanner() {
    const banner = $('workspace-truth-banner');
    if (!banner) return;
    const tier = state.truthTier || 'NONE';
    const info = M.truthTierInfo ? M.truthTierInfo(tier, state.previewMeta) : { preliminary: false, authoritative: false, label: '', detail: '' };
    const shouldShow = info.preliminary || (info.authoritative && state.promotedFromPreview);
    if (!shouldShow || !info.label) {
      banner.hidden = true;
      banner.replaceChildren();
      delete banner.dataset.tier;
      return;
    }
    banner.hidden = false;
    banner.dataset.tier = tier;
    const pill = el('span', 'workspace-truth-pill', info.label);
    const detail = el('span', '', info.detail);
    const main = append(el('div', 'workspace-truth-main'), pill, detail);
    const actions = el('div', 'run-summary-actions');
    if (info.preliminary) {
      actions.append(btn('View live progress', () => {
        const d = $('run-details');
        if (d) { d.open = true; d.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' }); }
      }, 'button subtle'));
    } else if (state.promotedFromPreview) {
      actions.append(btn('Dismiss', () => { state.promotedFromPreview = false; renderTruthBanner(); }, 'button subtle'));
    }
    banner.replaceChildren(main, actions);
  }
  function resetResult() { closeJobStream(); state.discovery?.abort(); state.publication?.abort(); $('result-opening').hidden = true; clearReadiness(); clearLiveTelemetryUi(); invalidate(); state.graphFocus = null; state.explorer = null; state.metricSubject = null; state.graph = null; state.resultJob = null; state.context = ''; state.contexts = []; state.reports = []; state.contextKinds = new Map(); state.discoveryEnriched = false; state.truthTier = 'NONE'; state.previewMeta = null; state.promotedFromPreview = false; state.scoreV2Cache = new Map(); renderTruthBanner(); document.querySelectorAll('[data-spring-view]').forEach(c => c.hidden = true); const stepper = $('job-stage-stepper'); if (stepper) { stepper.hidden = true; stepper.replaceChildren(); } const gapStrip = $('job-gap-action-strip'); if (gapStrip) { gapStrip.hidden = true; gapStrip.replaceChildren(); } $('context').replaceChildren(); notifyContextsUpdated(); $('analysis-workspace').hidden = true; $('welcome').hidden = false; updateResultStatus(); }
  function showRepositoryPrompt() {
    window.EvoGitHubAuth?.clearInlineAuthGate?.($('welcome'));
    $('repository-title').textContent = 'Explore your architecture';
    $('repository-description').hidden = true;
    $('repository-description').textContent = '';
    const ghBar = $('github-hub-bar');
    if (ghBar) { ghBar.hidden = true; ghBar.replaceChildren(); }
    $('welcome').querySelector('h2').textContent = 'Connect a repository';
    $('welcome').querySelector('p').textContent = 'Use a local folder or a Git repository to explore dependencies and findings.';
    const connectBtn = $('welcome').querySelector('button[data-action=connect]');
    if (connectBtn) connectBtn.hidden = false;
    const signInCta = $('welcome-github-signin');
    if (signInCta) signInCta.hidden = true;
  }
  function showMultiUserSignInPrompt() {
    $('repository-title').textContent = 'Sign in to your workspace';
    $('repository-description').textContent = 'Multi-user mode is active. Sign in with your workspace account or GitHub to browse organizations, import repositories, and run isolated analyses.';
    $('repository-description').hidden = false;
    const ghBar = $('github-hub-bar');
    if (ghBar) { ghBar.hidden = true; ghBar.replaceChildren(); }
    $('welcome').hidden = false;
    if (window.EvoGitHubAuth?.renderInlineAuthGate) {
      window.EvoGitHubAuth.renderInlineAuthGate($('welcome'));
      return;
    }
    $('welcome').querySelector('h2').textContent = 'Sign in with GitHub';
    $('welcome').querySelector('p').textContent = 'Authenticate with GitHub OAuth, Device Code, or a session-scoped RAM token to access your private tenant workspace.';
    const connectBtn = $('welcome').querySelector('button[data-action=connect]');
    if (connectBtn) connectBtn.hidden = true;
    let signInCta = $('welcome-github-signin');
    if (!signInCta) {
      signInCta = btn('Sign in with GitHub', () => window.EvoGitHubAuth?.openSignInModal?.(), 'button primary');
      signInCta.id = 'welcome-github-signin';
      if (connectBtn) connectBtn.after(signInCta);
      else $('welcome').append(signInCta);
    }
    signInCta.hidden = false;
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
    $('analyze').classList.toggle('is-busy', Boolean(busy));
    $('analyze').setAttribute('aria-busy', String(Boolean(busy)));
    $('analyze').textContent = state.retry && !state.submitting ? 'Retry submission' : busy ? 'Running…' : 'Run analysis';
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
      const authState = await window.EvoGitHubAuth?.refreshAuth?.({ notifyOnChange: false });
      window.EvoGitHubHub?.syncConnectTabsVisibility?.();
      $('view-insights').hidden = !state.capabilities.architectureInsightsEnabled;
      $('tab-architecture-chat').hidden = !state.capabilities.architectureChatEnabled;
      $('open-comparison').hidden = !state.capabilities.evolutionEnabled;
      $('open-bridge-captures').hidden = !state.capabilities.bridgesEnabled;
      $('workspace-tools').hidden = false;
      updateWorkspaceTool();

      const multiUserSignedOut = Boolean((authState?.me?.multiUserMode ?? state.capabilities.multiUserMode ?? false) && !authState?.me?.authenticated);
      if (multiUserSignedOut) {
        state.setups = [];
        state.connections = [];
        state.jobs = [];
        state.setup = null;
        state.job = null;
        resetResult();
        $('job-panel').hidden = true;
        showMultiUserSignInPrompt();
        document.querySelectorAll('[data-action=connect]').forEach(n => { n.disabled = !state.capabilities.repositoryConnectionsEnabled; n.title = n.disabled ? 'Repository connections are not enabled by this server.' : ''; });
        $('repositories').replaceChildren(el('p', 'muted small', 'Sign in with GitHub to view your repositories.'));
        $('history').replaceChildren(el('p', 'muted small', 'Sign in to view your recent runs.'));
        $('history-more').hidden = true;
        syncRunControls();
        return;
      }

      window.EvoGitHubAuth?.clearInlineAuthGate?.($('welcome'));
      const signInCta = $('welcome-github-signin');
      if (signInCta) signInCta.hidden = true;
      const [setups, connections] = await Promise.all([
        state.capabilities.analysisJobsEnabled ? api('/analysis/setups') : null,
        state.capabilities.repositoryConnectionsEnabled ? api('/analysis/connections') : null,
        refreshHistory()
      ]);
      state.setups = setups?.items ?? [];
      state.connections = connections?.items ?? [];
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
      else {
        if (!state.setup) showRepositoryPrompt();
        renderSidebar();
      }
      syncRunControls();
      void window.EvoGitHubHub?.refreshAuthStatus?.();
      if (!state.capabilities.analysisJobsEnabled) notice('This server is read-only. Start the workbench server to connect repositories and run analyses.');
    } catch (error) { state.online = false; $('connection-status').textContent = 'Server unavailable'; $('connection-dot').classList.remove('online'); syncRunControls(); renderSidebar(); notice(error.message, true); }
  }
  async function handleTenantSwitch() {
    closeJobStream();
    stopActivityClock();
    state.pollGeneration++;
    state.setup = null;
    state.job = null;
    state.retry = null;
    state.setups = [];
    state.connections = [];
    state.jobs = [];
    state.labels = new Map();
    state.chatFinding = null;
    state.chatSeedPrompt = '';
    try { localStorage.removeItem('evo_repository'); } catch (_) {}
    window.EvoGitHubHub?.clearCaches?.();
    window.EvoGitHubEnterprise?.clearCaches?.();
    resetResult();
    document.querySelectorAll('.analysis-content section[id^="panel-"]').forEach(p => p.replaceChildren());
    for (const [key, panel] of toolPanels) { panel.remove(); toolPanels.delete(key); }
    if ($('inspector-title')) $('inspector-title').textContent = '';
    if ($('inspector-body')) $('inspector-body').replaceChildren();
    if ($('job-events')) $('job-events').replaceChildren();
    if ($('job-telemetry-bar')) $('job-telemetry-bar').replaceChildren();
    if ($('result-opening')) $('result-opening').replaceChildren();
    if ($('toast-zone')) {
      for (const child of [...$('toast-zone').children]) {
        if (child.id === 'toast-live') child.textContent = '';
        else child.remove();
      }
    }
    $('job-panel').hidden = true;
    showRepositoryPrompt();
    renderSidebar();
    syncRunControls();
    await connectServer();
  }
  async function selectRepo(id) {
    stopActivityClock();
    state.setup = id; state.job = null; state.retry = null; state.pollGeneration++; resetResult(); $('job-panel').hidden = true;
    try { localStorage.setItem('evo_repository', id); } catch (_) { }
    $('search').value = ''; state.tab = 'overview'; state.historyLimit = 8;
    $('welcome').querySelector('h2').textContent = 'Ready to inspect your code';
    $('welcome').querySelector('p').textContent = 'Run an analysis to capture the current source, then explore its architecture and evidence.';
    $('welcome').querySelector('button').hidden = true;
    $('repository-title').textContent = repoLabel(id);
    $('repository-description').textContent = 'Run an analysis to inspect this repository. Results stay linked to their captured source.';
    $('repository-description').hidden = false;
    syncRunControls();
    void window.EvoGitHubHub?.syncConnectedRepoBar?.(id);
    closeMobileSidebar(); const latest = state.jobs.find(job => job.setup === id); renderSidebar();
    if (latest) await selectJob(latest, true);
  }
  async function connectAndAnalyze(source, ref = '') {
    const connection = await api('/analysis/connections', { source });
    if (ref) {
      window.EvoGitHubHub?.setSelectedRef?.(connection.setup, ref, null);
    }
    state.connections = (await api('/analysis/connections')).items;
    state.setups = (await api('/analysis/setups')).items;
    await selectRepo(connection.setup);
    if (!state.job && state.setup === connection.setup) {
      await safe(() => submit());
    }
    return connection;
  }
  async function connectRepository(event) {
    event.preventDefault(); $('connect-error').hidden = true; $('connect-submit').disabled = true;
    try {
      const policy = $('repository-policy').value;
      const intake = window.EvoGitHubHub?.getConnectSubmissionOptions?.() || { source: $('repository-source').value.trim(), ref: '', pullNumber: null, comparePrAfterConnect: false };
      const connection = await api('/analysis/connections', { source: intake.source, ...(policy.trim() ? { policy } : {}) });
      if (intake.ref || intake.pullNumber) {
        window.EvoGitHubHub?.setSelectedRef?.(connection.setup, intake.ref, intake.pullNumber);
      }
      state.connections = (await api('/analysis/connections')).items; state.setups = (await api('/analysis/setups')).items;
      $('connect-dialog').close(); await selectRepo(connection.setup);
      if (intake.comparePrAfterConnect && intake.pullNumber && window.EvoGitHubHub?.openPrComparisonModal) {
        await safe(() => window.EvoGitHubHub.openPrComparisonModal(connection.setup, { pullNumber: intake.pullNumber }));
      } else if (!state.job && state.setup === connection.setup) {
        await safe(() => submit());
      }
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
  function updateJobProgressUi(job, submitting = false) {
    const panel = $('job-panel');
    if (!panel) return;
    const live = state.liveProgress && (!job || state.liveProgress.jobId === job.id) ? state.liveProgress : null;
    const progress = M.jobProgress(job, submitting, live);
    panel.classList.toggle('is-det', progress.determinate);
    panel.classList.toggle('is-done', progress.done);
    panel.dataset.tone = progress.tone;
    const fill = $('job-progress-fill');
    if (fill) fill.style.setProperty('--p', progress.ratio.toFixed(3));
    const pill = $('job-status-pill');
    if (pill) {
      pill.textContent = progress.statusLabel;
      pill.dataset.tone = progress.tone;
      pill.hidden = !job && !submitting;
    }
  }
  function findPreviousGraphForComparison(job) {
    if (!job?.result?.graph) return null;
    const inc = job.incremental || state.liveProgress?.incremental || state.incrementalSummary;
    const fromInc = inc?.baseGraphId;
    if (typeof fromInc === 'string' && /^sha256:[0-9a-f]{64}$/.test(fromInc) && fromInc !== job.result.graph) {
      return fromInc;
    }
    const older = state.jobs.find(item => item.setup === job.setup && item.id !== job.id && item.result?.graph && item.result.graph !== job.result.graph);
    return older?.result?.graph || null;
  }
  function renderLiveTelemetry(job, progress = null) {
    const activeProgress = progress || (state.liveProgress && job && state.liveProgress.jobId === job.id ? state.liveProgress : null);
    const liveInline = $('job-live-inline');
    const liveMetrics = $('job-live-metrics');
    const moduleBox = $('job-module-pills');
    const incStrip = $('job-incremental-strip');
    const compareBtn = $('job-compare-prev');

    const tp = activeProgress && M.formatThroughput ? M.formatThroughput(activeProgress) : { rateText: '', etaText: '', stageCountText: '' };
    const pills = [];
    if (tp.stageCountText) pills.push(el('span', 'job-metric-pill', tp.stageCountText));
    if (tp.rateText) pills.push(el('span', 'job-metric-pill is-accent', tp.rateText));
    if (tp.etaText && job && !M.terminal(job)) pills.push(el('span', 'job-metric-pill', tp.etaText));
    if (activeProgress?.truthTier && M.isPreliminaryTier?.(activeProgress.truthTier)) {
      const ti = M.truthTierInfo(activeProgress.truthTier, activeProgress.preview || state.previewMeta);
      pills.push(el('span', 'job-metric-pill is-warn', ti.shortLabel));
    }
    if (activeProgress?.message && job && !M.terminal(job)) {
      pills.push(el('span', 'small muted', activeProgress.message));
    }
    if (liveInline) {
      liveInline.hidden = !pills.length || (job && M.terminal(job));
      liveInline.replaceChildren(...pills.map(p => p.cloneNode(true)));
    }
    if (liveMetrics) {
      liveMetrics.hidden = !pills.length;
      liveMetrics.replaceChildren(...pills);
    }

    const modules = activeProgress?.modules || [];
    if (moduleBox) {
      moduleBox.hidden = !modules.length;
      moduleBox.replaceChildren();
      for (const mod of modules) {
        const clickable = Boolean(mod.contextId && state.contexts?.some(c => c.identity === mod.contextId));
        const icon = mod.status === 'COMPLETED' ? '✓' : mod.status === 'REUSED' ? '⚡' : mod.status === 'RUNNING' ? '◐' : mod.status === 'FAILED' ? '✕' : '○';
        const text = `${icon} ${mod.module}${mod.filesTotal > 0 ? ` (${mod.filesDone}/${mod.filesTotal})` : ''}`;
        const chip = clickable
          ? btn(text, () => {
              state.graphFocus = null; state.explorer = null; state.metricSubject = null;
              state.context = mod.contextId;
              $('context').value = mod.contextId;
              notifyContextsUpdated();
              return loadTab(state.tab);
            }, 'job-module-pill is-clickable')
          : el('span', 'job-module-pill', text);
        chip.dataset.status = mod.status || 'PENDING';
        chip.title = [mod.module, mod.status, mod.reason || null, mod.elapsedMillis ? formatDuration(mod.elapsedMillis) : null].filter(Boolean).join(' · ');
        moduleBox.append(chip);
      }
    }

    const rawInc = activeProgress?.incremental || job?.incremental || state.incrementalSummary;
    const inc = rawInc && M.summarizeIncremental ? M.summarizeIncremental(rawInc) : null;
    if (incStrip) {
      if (inc && (inc.hasSavings || inc.changedFiles > 0 || inc.mode !== 'COLD')) {
        const incNodes = [
          el('span', 'job-metric-pill is-accent', inc.mode === 'NO_CHANGE' ? 'No changes' : inc.mode === 'FALLBACK_FULL' ? 'Full recompute' : 'Incremental delta')
        ];
        if (inc.totalFiles > 0) incNodes.push(el('span', 'job-metric-pill', `${inc.reusedFiles.toLocaleString()}/${inc.totalFiles.toLocaleString()} files reused (${inc.reusePct}%)`));
        if (inc.changedFiles > 0) incNodes.push(el('span', 'job-metric-pill', `+${inc.addedFiles} ~${inc.modifiedFiles} -${inc.deletedFiles}`));
        if (inc.abiEarlyCutoffCount > 0) incNodes.push(el('span', 'job-metric-pill is-accent', `${inc.abiEarlyCutoffCount} ABI early cutoff${inc.abiEarlyCutoffCount === 1 ? '' : 's'}`));
        if (inc.impactedDependentsCount > 0) incNodes.push(el('span', 'job-metric-pill', `${inc.impactedDependentsCount} impacted dep${inc.impactedDependentsCount === 1 ? '' : 's'}`));
        if (inc.reusedModules > 0) incNodes.push(el('span', 'job-metric-pill', `${inc.reusedModules} module${inc.reusedModules === 1 ? '' : 's'} reused`));
        if (inc.formattedTimeSaved) incNodes.push(el('span', 'job-metric-pill is-accent', `Est. ${inc.formattedTimeSaved} saved`));
        if (inc.baseSnapshotId) incNodes.push(el('span', 'job-metric-pill', `Base ${M.shortDigest(inc.baseSnapshotId)}`));
        incStrip.hidden = false;
        incStrip.replaceChildren(...incNodes);
      } else {
        incStrip.hidden = true;
        incStrip.replaceChildren();
      }
    }

    if (compareBtn) {
      const prevGraph = job && M.terminal(job) ? findPreviousGraphForComparison(job) : null;
      const showCompare = Boolean(state.capabilities?.evolutionEnabled && prevGraph && job?.result?.graph);
      compareBtn.hidden = !showCompare;
      if (showCompare) {
        const setupObj = state.setups.find(s => s.id === job.setup);
        const repoId = typeof setupObj?.repository === 'string' ? setupObj.repository : (setupObj?.repository?.value || '');
        compareBtn.onclick = () => {
          location.hash = M.buildCompareHash({ left: prevGraph, right: job.result.graph, repo: repoId, autorun: true });
        };
      }
    }
    window.EvoGitHubHub?.syncPipelineStepper?.(job, activeProgress);
  }
  function showSubmittingLog(mode = 'FULL') {
    stopActivityClock();
    $('job-panel').hidden = false;
    $('welcome').hidden = true;
    $('job-description').textContent = 'Starting analysis…';
    $('job-elapsed').textContent = '0 s';
    $('job-failure').hidden = true;
    showProgressConnection();
    const modeNote = $('job-mode');
    modeNote.textContent = mode === 'FAST_ARCHITECTURE'
      ? 'Quick analysis · reduced Java detail · installed Node packages inventoried'
      : mode === 'FULL' ? 'Deep analysis' : '';
    modeNote.hidden = !modeNote.textContent;
    const timing = $('job-timing');
    timing.dataset.job = '__submitting__';
    timing.open = false;
    timing.hidden = true;
    state.telemetryJob = null;
    $('job-telemetry-bar').replaceChildren();
    const preview = $('job-score-preview');
    preview.dataset.job = '__submitting__';
    preview.hidden = true;
    preview.textContent = '';
    $('cancel-job').hidden = true;
    $('cancel-job').disabled = true;
    const details = $('run-details');
    details.dataset.job = '__submitting__';
    details.open = true;
    const list = $('job-events');
    const stepDetails = append(el('details'),
      append(el('summary'),
        makeStepIndicator(),
        el('span', 'step-title', 'Submitting analysis run'),
        el('span', 'step-duration', '0 s')),
      el('p', 'step-note', 'Sending the analysis request and preparing the live activity log.'));
    stepDetails.dataset.stage = 'QUEUED';
    const item = append(el('li'), stepDetails);
    item.dataset.event = '__submitting__';
    item.dataset.s = 'run';
    item.setAttribute('aria-current', 'step');
    list.replaceChildren(item);
    const trackFill = $('job-step-track-fill');
    if (trackFill) trackFill.style.setProperty('--u454-p', '0');
    updateJobProgressUi(null, true);
    requestAnimationFrame(syncStepTrackGeometry);
  }
  async function submit(force = false) {
    if (!state.setup || state.submitting || (state.job && !M.terminal(state.job))) return;
    notice('');
    const setup = state.setup, generation = state.pollGeneration;
    const mode = $('analysis-mode-field').hidden ? 'FULL' : $('analysis-mode').value;
    const selectedRef = window.EvoGitHubHub?.getSelectedRef?.(setup) || '';
    const request = state.retry ||= { setup, idempotencyKey: crypto.randomUUID(), ...(force ? { force: true } : {}), ...(mode === 'FAST_ARCHITECTURE' ? { mode: 'FAST_ARCHITECTURE' } : {}), ...(selectedRef ? { ref: selectedRef } : {}) };
    state.autoOpenLog = true;
    $('run-options').open = false; state.submitting = request; syncRunControls(); updateResultStatus();
    showSubmittingLog(mode);
    try {
      const job = await api('/analysis/jobs', request, undefined, 'POST', 15000);
      if (state.retry === request) state.retry = null;
      if (state.setup === setup && state.pollGeneration === generation) await selectJob(job, true);
      await refreshHistory();
    } catch (error) {
      state.autoOpenLog = false;
      if (state.setup === setup && state.pollGeneration === generation) {
        if (state.job) {
          renderJob(state.job);
        } else {
          $('job-panel').hidden = true;
          $('run-details').dataset.job = '';
          $('job-events').replaceChildren();
          if (!state.graph) $('welcome').hidden = false;
        }
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
      const totalMs = Math.max(1, Number(telemetry.totalElapsedMillis) || telemetry.stages.reduce((acc, s) => acc + (Number(s.durationMillis) || 0), 0) || 1);
      for (const { stage, durationMillis } of telemetry.stages) {
        const pct = Math.min(100, Math.max(2, Math.round(((Number(durationMillis) || 0) / totalMs) * 100)));
        const dd = el('dd', 'job-timing-val');
        const fillBar = el('span', 'job-timing-bar');
        const inner = el('i');
        inner.style.width = pct + '%';
        fillBar.append(inner);
        dd.append(el('span', 'job-timing-ms', formatDuration(durationMillis)), fillBar);
        stages.append(el('dt', '', analysisSteps[stage]?.[0] || stage), dd);
      }
      const nodes = [total, stages];
      const inc = telemetry.incremental && M.summarizeIncremental ? M.summarizeIncremental(telemetry.incremental) : null;
      if (inc) {
        const incGrid = el('dl', 'job-incremental-grid');
        const rows = [
          ['Reuse mode', inc.mode + (inc.fallbackReason ? ' · ' + inc.fallbackReason : '')],
          ['Files reused', inc.totalFiles > 0 ? `${inc.reusedFiles.toLocaleString()} / ${inc.totalFiles.toLocaleString()} (${inc.reusePct}%)` : `${inc.reusedFiles.toLocaleString()}`],
          ['File delta', `+${inc.addedFiles} added · ~${inc.modifiedFiles} mod · -${inc.deletedFiles} del`],
          ['ABI early cutoffs', `${inc.abiEarlyCutoffCount.toLocaleString()} · ${inc.impactedDependentsCount.toLocaleString()} impacted`],
          ['Modules reused', inc.recomputedModules > 0 ? `${inc.reusedModules} / ${inc.reusedModules + inc.recomputedModules}` : `${inc.reusedModules}`],
          ...(inc.formattedTimeSaved ? [['Est. time saved', inc.formattedTimeSaved]] : []),
          ...(inc.baseSnapshotId ? [['Base snapshot', M.shortDigest(inc.baseSnapshotId)]] : [])
        ];
        for (const [k, v] of rows) {
          incGrid.append(append(el('div', 'job-incremental-stat'), el('dt', '', k), el('dd', '', v)));
        }
        nodes.push(el('h3', 'small', 'Incremental & Cache Telemetry'), incGrid);
      }
      if (telemetry.cacheMetrics && Object.keys(telemetry.cacheMetrics).length) {
        const cachePills = el('div', 'job-live-metrics');
        for (const [k, v] of Object.entries(telemetry.cacheMetrics)) {
          cachePills.append(el('span', 'job-metric-pill', `${k}: ${Number(v).toLocaleString()}`));
        }
        nodes.push(cachePills);
      }
      bar.replaceChildren(...nodes);
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
  function syncStepTrackGeometry() {
    const list = $('job-events');
    const wrap = list?.closest('.u454-wrap');
    const track = wrap?.querySelector('.u454-tr');
    if (!list || !wrap || !track) return;
    const icons = list.querySelectorAll('.u454-ic');
    if (icons.length < 2) {
      track.style.top = '0px';
      track.style.height = '0px';
      return;
    }
    const wrapRect = wrap.getBoundingClientRect();
    const first = icons[0].getBoundingClientRect();
    const last = icons[icons.length - 1].getBoundingClientRect();
    if (!first.height || !last.height) return;
    const top = Math.max(0, first.top + first.height / 2 - wrapRect.top);
    const height = Math.max(0, (last.top + last.height / 2) - (first.top + first.height / 2));
    track.style.top = top.toFixed(1) + 'px';
    track.style.height = height.toFixed(1) + 'px';
  }
  function makeStepIndicator() {
    const ic = el('span', 'u454-ic');
    ic.setAttribute('aria-hidden', 'true');
    ic.innerHTML = '<i class="u454-d"></i><i class="u454-sp"></i>' +
      '<span class="u454-ck"><svg viewBox="0 0 24 24"><path d="m6 12.5 4 4 8-9"/></svg></span>' +
      '<span class="u454-err"><svg viewBox="0 0 24 24"><path d="M8 8l8 8M16 8l-8 8"/></svg></span>' +
      '<span class="u454-warn"><svg viewBox="0 0 24 24"><path d="M8 12h8"/></svg></span>';
    return ic;
  }
  function renderActivity(job) {
    const list = $('job-events'), events = [...(job.events ?? [])];
    if (M.terminal(job) && events.at(-1)?.stage !== 'FINISHED' && job.finishedAt) {
      events.push({ stage: 'FINISHED', at: job.finishedAt });
    }
    const items = new Map([...list.children].map(item => [item.dataset.event, item]));
    const remaining = new Set(items.values());
    const cancelled = job.state === 'CANCELLED';
    const hardFailed = job.state === 'FAILED' || job.state === 'TIMED_OUT';
    const failed = hardFailed || cancelled;
    const failureCode = job.error || job.stop;
    let doneCount = 0;
    for (const [index, event] of events.entries()) {
      const key = event.stage + '/' + event.at;
      let item = items.get(key);
      if (!item) {
        const time = el('time', 'step-started', new Date(event.at).toLocaleTimeString());
        time.dateTime = event.at;
        const details = append(el('details'), append(el('summary'),
          makeStepIndicator(), el('span', 'step-title'), el('span', 'step-duration')),
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
      const stepState = active ? 'run'
        : ((finished || interrupted) && hardFailed) ? 'fail'
        : ((finished || interrupted) && cancelled) ? 'warn'
        : 'done';
      item.dataset.s = stepState;
      if (stepState === 'done') doneCount++;
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
    const trackFill = $('job-step-track-fill');
    if (trackFill) {
      const denom = Math.max(1, events.length - 1);
      const ratio = events.length <= 1 ? (M.terminal(job) ? 1 : 0) : Math.min(1, Math.max(0, (doneCount - (M.terminal(job) ? 0 : 0.25)) / denom));
      trackFill.style.setProperty('--u454-p', ratio.toFixed(3));
    }
    requestAnimationFrame(syncStepTrackGeometry);
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
    const prevState = state.observedJobStates.get(job.id);
    const wasRunning = prevState && !M.terminal({ state: prevState });
    state.observedJobStates.set(job.id, job.state);
    const summary = job.state === 'CANCELLING' ? 'Cancelling run' : runOutcomes[job.state] ?? analysisSteps[job.stage]?.[0] ?? 'Analysis in progress';
    if ($('job-description').textContent !== summary) $('job-description').textContent = summary;
    updateJobProgressUi(job, false);
    const details = $('run-details');
    if (details.dataset.job !== job.id) {
      details.dataset.job = job.id;
      details.open = M.shouldAutoExpandRunLog(job, state.autoOpenLog);
      state.autoOpenLog = false;
      $('job-events').replaceChildren();
      showProgressConnection();
    }
    if (wasRunning && M.terminal(job) && window.EvoToast) {
      if (job.state === 'FAILED' || job.state === 'TIMED_OUT') {
        window.EvoToast.push({ kind: 'err', title: runOutcomes[job.state] || 'Analysis failed', detail: failureMessages[job.error || job.stop] || 'Inspect the activity log for details.' });
      } else if (job.state === 'CANCELLED') {
        window.EvoToast.push({ kind: 'warn', title: 'Analysis cancelled', detail: 'Run stopped before completion.' });
      } else {
        window.EvoToast.push({ kind: 'ok', title: job.reuse ? 'Saved analysis reused' : 'Analysis complete', detail: repoLabel(job.setup) });
      }
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
    if (job.result?.graph) {
      $('repository-description').textContent = 'Captured ' + new Date(job.reuse?.sourceFinishedAt || job.finishedAt || job.submittedAt).toLocaleString() + ' · Run a new analysis after changing the source.';
      $('repository-description').hidden = false;
    }
    $('cancel-job').hidden = M.terminal(job); $('cancel-job').disabled = job.state === 'CANCELLING'; syncRunControls(); updateResultStatus();
    renderActivity(job);
    renderLiveTelemetry(job);
    syncActivityClock(job);
    showRunFailure(job);
    requestScorePreview(job);
    window.EvoGitHubHub?.syncPipelineStepper?.(job, state.liveProgress);
    void window.EvoGitHubHub?.syncPartialGapStrip?.(job);
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
  function shouldUseJobSse(job) {
    if (typeof EventSource !== 'function' || !job) return false;
    return Boolean(state.capabilities?.analysisJobEventsEnabled || state.capabilities?.progressiveAnalysisEnabled
      || job.publicationStage || job.previewGraphId);
  }
  function connectJobStream(id, generation) {
    closeJobStream();
    if (!selectedJob(id, generation)) return;
    let source;
    try {
      source = new EventSource('/api/v1/analysis/jobs/' + id + '/events');
    } catch (_) {
      poll(id, generation);
      return;
    }
    state.eventSource = source;
    let opened = false;
    let consecutiveErrors = 0;
    let resyncController = null;
    const markAlive = () => { opened = true; consecutiveErrors = 0; showProgressConnection(); };
    const parsePayload = event => {
      try { return event?.data ? JSON.parse(event.data) : null; }
      catch (_) { return null; }
    };
    const syncPreviewIfReady = (graphId, truthTier, meta) => {
      if (!graphId || !M.isPreliminaryTier?.(truthTier)) return;
      if (state.graph === graphId && state.truthTier === truthTier) return;
      void safe(() => loadPreviewGraph({ graphId, truthTier, ...meta }, id, generation));
    };

    source.addEventListener('snapshot', event => {
      markAlive();
      if (!selectedJob(id, generation)) { closeJobStream(); return; }
      const snap = parsePayload(event);
      if (!snap) return;
      state.liveProgress = snap;
      if (snap.incremental) state.incrementalSummary = snap.incremental;
      if (snap.stage && state.job && snap.stage !== state.job.stage) {
        state.job = { ...state.job, stage: snap.stage, state: snap.state || state.job.state, revision: snap.revision ?? state.job.revision };
        renderJob(state.job);
      } else {
        updateJobProgressUi(state.job, false);
        renderLiveTelemetry(state.job, snap);
      }
      if (snap.previewGraphId && M.isPreliminaryTier?.(snap.truthTier)) {
        syncPreviewIfReady(snap.previewGraphId, snap.truthTier, snap.preview || {});
      }
    });

    source.addEventListener('stage_started', event => {
      markAlive();
      if (!selectedJob(id, generation)) { closeJobStream(); return; }
      const data = parsePayload(event);
      if (!data?.stage || !state.job) return;
      const exists = (state.job.events || []).some(e => e.stage === data.stage);
      const events = exists ? state.job.events : [...(state.job.events || []), { stage: data.stage, at: new Date().toISOString() }];
      state.job = { ...state.job, state: data.state || 'RUNNING', stage: data.stage, revision: Math.max(state.job.revision || 0, data.revision || 0), events };
      if (state.liveProgress) state.liveProgress = { ...state.liveProgress, stage: data.stage, overallRatio: data.overallRatio ?? state.liveProgress.overallRatio };
      renderJob(state.job);
    });

    source.addEventListener('progress_update', event => {
      markAlive();
      if (!selectedJob(id, generation)) { closeJobStream(); return; }
      const data = parsePayload(event);
      if (!data) return;
      state.liveProgress = { ...(state.liveProgress || { jobId: id, modules: [] }), ...data, jobId: id };
      updateJobProgressUi(state.job, false);
      renderLiveTelemetry(state.job, state.liveProgress);
    });

    const handleModuleEvent = event => {
      markAlive();
      if (!selectedJob(id, generation)) { closeJobStream(); return; }
      const data = parsePayload(event);
      if (!data) return;
      const base = state.liveProgress || { jobId: id, modules: [] };
      let modules = Array.isArray(base.modules) ? [...base.modules] : [];
      if (Array.isArray(data.modules)) {
        modules = data.modules;
      } else if (data.module) {
        const idx = modules.findIndex(m => m.module === data.module);
        const entry = {
          module: data.module, status: data.status || 'RUNNING',
          filesDone: data.filesDone ?? 0, filesTotal: data.filesTotal ?? 0,
          elapsedMillis: data.elapsedMillis ?? 0, contextId: data.contextId || null, reason: data.reason || null
        };
        if (idx >= 0) modules[idx] = entry; else modules.push(entry);
      }
      state.liveProgress = { ...base, jobId: id, modules };
      renderLiveTelemetry(state.job, state.liveProgress);
    };
    source.addEventListener('module_progress', handleModuleEvent);
    source.addEventListener('module_completed', handleModuleEvent);

    source.addEventListener('preview_ready', event => {
      markAlive();
      if (!selectedJob(id, generation)) { closeJobStream(); return; }
      const data = parsePayload(event);
      if (!data?.graphId) return;
      state.liveProgress = {
        ...(state.liveProgress || { jobId: id, modules: [] }),
        jobId: id,
        truthTier: data.truthTier,
        previewGraphId: data.graphId,
        preview: data
      };
      renderLiveTelemetry(state.job, state.liveProgress);
      syncPreviewIfReady(data.graphId, data.truthTier, data);
    });

    source.addEventListener('incremental_summary', event => {
      markAlive();
      if (!selectedJob(id, generation)) { closeJobStream(); return; }
      const data = parsePayload(event);
      if (!data?.incremental) return;
      state.incrementalSummary = data.incremental;
      if (state.liveProgress) state.liveProgress = { ...state.liveProgress, incremental: data.incremental };
      renderLiveTelemetry(state.job, state.liveProgress);
    });

    source.addEventListener('resync_required', () => {
      markAlive();
      if (!selectedJob(id, generation)) return;
      resyncController?.abort();
      resyncController = new AbortController();
      const resyncSignal = resyncController.signal;
      safe(async () => {
        const [latestJob, latestProgress] = await Promise.all([
          api('/analysis/jobs/' + id, undefined, resyncSignal, 'GET', 8000),
          api('/analysis/jobs/' + id + '/progress', undefined, resyncSignal, 'GET', 8000).catch(() => null)
        ]);
        if (resyncSignal.aborted || !selectedJob(id, generation)) return;
        if (latestProgress) state.liveProgress = latestProgress;
        state.job = latestJob;
        renderJob(latestJob);
      });
    });

    const onTerminalEvent = () => {
      resyncController?.abort();
      closeJobStream();
      if (!selectedJob(id, generation)) return;
      safe(async () => {
        const finalJob = await api('/analysis/jobs/' + id, undefined, undefined, 'GET', 10000);
        if (!selectedJob(id, generation)) return;
        const hadPreview = M.isPreliminaryTier?.(state.truthTier);
        state.job = finalJob;
        state.jobs = state.jobs.map(item => item.id === id ? finalJob : item);
        if (finalJob.incremental) state.incrementalSummary = finalJob.incremental;
        renderJob(finalJob);
        renderSidebar();
        clearReadiness();
        if (finalJob.result?.graph) {
          await loadGraph(finalJob, id, generation, { preserveInteraction: hadPreview, promotedFromPreview: hadPreview });
        }
        if (selectedJob(id, generation)) await refreshHistory();
      });
    };
    source.addEventListener('job_completed', onTerminalEvent);
    source.addEventListener('job_failed', onTerminalEvent);
    source.addEventListener('job_cancelled', onTerminalEvent);

    source.onerror = () => {
      if (!selectedJob(id, generation) || (state.job && M.terminal(state.job))) {
        closeJobStream();
        return;
      }
      consecutiveErrors++;
      if (opened && source.readyState === EventSource.CONNECTING && consecutiveErrors <= 2) {
        showProgressConnection('Live stream interrupted — reconnecting with Last-Event-ID…');
        return;
      }
      resyncController?.abort();
      closeJobStream();
      if (!opened) showProgressConnection('Live event stream unavailable — switched to polling.');
      poll(id, generation);
    };
  }
  async function loadPreviewGraph(preview, jobId, generation) {
    if (!preview?.graphId || !selectedJob(jobId, generation)) return;
    const graphId = preview.graphId;
    const wasPreliminary = M.isPreliminaryTier?.(state.truthTier);
    const keepInteraction = Boolean(state.graph && (wasPreliminary || !$('analysis-workspace').hidden));
    const savedChatDraft = document.querySelector('#panel-architecture-chat textarea')?.value ?? null;
    if (state.cy && !state.cy.destroyed()) {
      state.pendingGraphViewport = { zoom: state.cy.zoom(), pan: state.cy.pan(), context: state.context };
    }
    state.discovery?.abort();
    const controller = new AbortController(); state.discovery = controller;
    let discovery;
    try {
      discovery = await api('/graphs/' + graphId, undefined, controller.signal, 'GET', 15000);
    } catch (error) {
      if (error.status === 410 || error.code === 'PREVIEW_SUPERSEDED') {
        const refreshed = await api('/analysis/jobs/' + jobId, undefined, undefined, 'GET', 8000).catch(() => null);
        if (refreshed && selectedJob(jobId, generation)) {
          state.job = refreshed;
          if (refreshed.result?.graph) return loadGraph(refreshed, jobId, generation, { preserveInteraction: true, promotedFromPreview: true });
          const nextPreviewId = error.supersededBy || refreshed.previewGraphId;
          if (nextPreviewId && nextPreviewId !== graphId) {
            return loadPreviewGraph({ graphId: nextPreviewId, truthTier: refreshed.publicationStage || 'PARTIAL_CONTEXTS', ...(refreshed.preview || {}) }, jobId, generation);
          }
        }
      }
      return;
    }
    if (state.discovery !== controller || !selectedJob(jobId, generation)) return;
    state.snapshot = discovery.snapshot;
    state.publication?.abort(); state.publication = new AbortController();
    state.graph = graphId;
    state.resultJob = state.job;
    state.contexts = discovery.contexts || [];
    state.reports = [];
    state.reportIds = [];
    state.reportIndexLoaded = true;
    state.discoveryEnriched = true;
    state.scoreV2Cache = new Map();
    state.truthTier = discovery.truthTier || preview.truthTier || 'PRELIMINARY_STRUCTURE';
    state.previewMeta = { ...preview, ...(discovery.previewMeta || {}) };
    state.promotedFromPreview = false;
    state.moduleLabels = new Map(); state.contextKinds = new Map(); state.contextLoads = new Map(); state.contextLabels = new Map();
    $('analysis-workspace').hidden = false; $('welcome').hidden = true;
    $('job-score-preview').hidden = true;
    $('context').replaceChildren(el('option', '', 'Choose a context…')); $('context').firstChild.value = '';
    for (const context of state.contexts) {
      const meta = formatContext(context);
      const option = el('option', '', meta.label);
      option.value = context.identity;
      option.title = context.identity;
      applyOptionMeta(option, meta, false);
      $('context').append(option);
      recordContextMetadata(context, meta);
    }
    state.context = M.selectDefaultContext ? M.selectDefaultContext(state.contexts, state.snapshot, state.context)
      : (state.contexts.find(c => c.identity === state.snapshot)?.identity ?? state.contexts[0]?.identity ?? '');
    $('context').value = state.context;
    notifyContextsUpdated();
    renderTruthBanner();
    updateResultStatus();
    if (!wasPreliminary && window.EvoToast) {
      window.EvoToast.push({ kind: 'info', title: 'Preliminary structure ready', detail: 'Explore modules, graph, and captured source while deep analysis continues.' });
    }
    await loadTab(state.tab, { keepInspector: keepInteraction });
    if (savedChatDraft) {
      const ta = document.querySelector('#panel-architecture-chat textarea');
      if (ta && !ta.value) {
        ta.value = savedChatDraft;
        ta.dispatchEvent(new Event('input', { bubbles: true }));
      }
    }
  }
  async function selectJob(job, keepPrevious = false) {
    closeJobStream();
    state.job = job; state.setup = job.setup; state.retry = null; state.pollGeneration++;
    state.discovery?.abort();
    clearReadiness();
    clearLiveTelemetryUi();
    if (!keepPrevious || state.resultJob?.setup !== job.setup) resetResult();
    if (job.incremental) state.incrementalSummary = job.incremental;
    $('repository-title').textContent = repoLabel(job.setup); renderJob(job); renderSidebar();
    const generation = state.pollGeneration;
    const previous = keepPrevious && !state.graph && state.jobs.find(item => item.setup === job.setup && item.id !== job.id && item.result?.graph);
    if (!M.terminal(job)) {
      if (shouldUseJobSse(job)) connectJobStream(job.id, generation);
      else poll(job.id, generation);
    }
    requestReadiness(job, generation);
    try {
      if (job.result?.graph) await loadGraph(job, job.id, generation);
      else if (job.previewGraphId && M.isPreliminaryTier?.(job.publicationStage)) {
        await loadPreviewGraph({ graphId: job.previewGraphId, truthTier: job.publicationStage, ...(job.preview || {}) }, job.id, generation);
      } else if (previous) await loadGraph(previous, job.id, generation);
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
      if (job.incremental) state.incrementalSummary = job.incremental;
      renderJob(job);
      renderSidebar();
      if (!M.terminal(job) && job.previewGraphId && M.isPreliminaryTier?.(job.publicationStage) && state.graph !== job.previewGraphId) {
        void safe(() => loadPreviewGraph({ graphId: job.previewGraphId, truthTier: job.publicationStage, ...(job.preview || {}) }, id, generation));
      }
    } else if (job.revision === state.job.revision) syncActivityClock(job);
    if (M.terminal(state.job)) {
      clearReadiness();
      const hadPreview = M.isPreliminaryTier?.(state.truthTier);
      if (state.job.result?.graph) await loadGraph(state.job, id, generation, { preserveInteraction: hadPreview, promotedFromPreview: hadPreview });
      if (selectedJob(id, generation)) await refreshHistory();
      return;
    }
    requestReadiness(state.job, generation);
    const age = performance.now() - since;
    setTimeout(() => safe(() => poll(id, generation, since)), age < 2000 ? 250 : age < 30000 ? 1000 : 2500);
  }
  async function loadGraph(publication, jobId, generation, opts = {}) {
    const { graph, snapshot, contexts } = publication.result;
    const preserveInteraction = Boolean(opts.preserveInteraction);
    const promotedFromPreview = Boolean(opts.promotedFromPreview || M.isPreliminaryTier?.(state.truthTier));
    const displayed = state.resultJob;
    if (state.graph === graph && displayed && !M.isPreliminaryTier?.(state.truthTier) && (displayed.id === publication.id ||
      publication.reuse?.sourceJob === (displayed.reuse?.sourceJob ?? displayed.id))) {
      state.resultJob = publication; updateResultStatus(); return;
    }
    const savedChatDraft = preserveInteraction ? (document.querySelector('#panel-architecture-chat textarea')?.value ?? null) : null;
    if (preserveInteraction && state.cy && !state.cy.destroyed()) {
      state.pendingGraphViewport = { zoom: state.cy.zoom(), pan: state.cy.pan(), context: state.context };
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
      if (!preserveInteraction) {
        loading.replaceChildren(el('p', '', 'Reading the saved result…'));
        loading.hidden = false;
      }
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
    state.truthTier = 'DEEP_SEMANTIC_COMPLETE';
    state.previewMeta = null;
    state.promotedFromPreview = promotedFromPreview;
    state.scoreV2Cache = new Map();
    renderTruthBanner();
    void window.EvoGitHubEnterprise?.loadCodeowners?.(graph);
    state.reportIds = [...new Set([...(publication.result.reports ?? []), ...(discovery.reports ?? [])])];
    state.reportIndexLoaded = !fromReceipt;
    state.discoveryEnriched = !fromReceipt;
    state.repositoryScore = null;
    state.policyRuns = new Map();
    state.activePolicyReports = new Map();
    state.reportLoad = null;
    state.moduleLabels = new Map(); state.contextKinds = new Map(); state.contextLoads = new Map(); state.contextLabels = new Map();
    $('analysis-workspace').hidden = false; $('welcome').hidden = true;
    $('job-score-preview').hidden = true;
    $('context').replaceChildren(el('option', '', 'Choose a context…')); $('context').firstChild.value = '';
    for (const context of state.contexts) {
      const meta = formatContext(context);
      const option = el('option', '', meta.label);
      option.value = context.identity;
      option.title = context.identity;
      applyOptionMeta(option, meta, false);
      $('context').append(option);
      recordContextMetadata(context, meta);
    }
    state.context = M.selectDefaultContext ? M.selectDefaultContext(state.contexts, state.snapshot, state.context)
      : (state.contexts.some(context => context.identity === state.context)
          ? state.context
          : (state.contexts.find(context => context.identity === state.snapshot)?.identity ?? state.contexts[0]?.identity ?? ''));
    $('context').value = state.context;
    notifyContextsUpdated();
    renderLiveTelemetry(publication);
    if (hasUnresolvedContexts() && (!fromReceipt || Boolean(state.capabilities?.contextMetadataEnabled))) {
      void labelContextOptions();
    }
    if (openChatOnFirstResult && state.capabilities?.architectureChatEnabled) {
      openChatOnFirstResult = false;
      await loadTab('architecture-chat', { keepInspector: preserveInteraction });
      return;
    }
    await loadTab(state.tab, { keepInspector: preserveInteraction });
    if (savedChatDraft) {
      const ta = document.querySelector('#panel-architecture-chat textarea');
      if (ta && !ta.value) {
        ta.value = savedChatDraft;
        ta.dispatchEvent(new Event('input', { bubbles: true }));
      }
    }
  }
  function notifyContextsUpdated() {
    document.dispatchEvent(new CustomEvent('evo:contexts-updated'));
    emitSnapshotChanged();
  }
  function contextRecord(identity) {
    return state.contexts?.find(c => c.identity === identity) || { identity };
  }
  function formatContext(context, provenance = null) {
    if (M.formatContextOption) return M.formatContextOption(context, state.snapshot, provenance);
    const id = typeof context === 'string' ? context : String(context?.identity || '');
    return { label: id === state.snapshot ? 'Whole repository' : (context?.label || id), badge: 'CTX', subtitle: '', kind: 'context' };
  }
  function applyOptionMeta(option, meta, isGraphSelect = false) {
    if (!option || !meta) return;
    option.textContent = isGraphSelect && option.value === state.snapshot ? 'Repository source evidence' : meta.label;
    option.dataset.badge = meta.badge || '';
    option.dataset.sub = meta.subtitle || '';
    option.dataset.kind = meta.kind || '';
  }
  function recordContextMetadata(context, meta) {
    if (!context?.identity) return;
    if (context.family) {
      state.contextKinds.set(context.identity, context.family);
      if (context.family === 'spring.context') {
        document.querySelectorAll('[data-spring-view]').forEach(control => control.hidden = false);
      }
    }
    if (context.identity === state.snapshot || context.label || context.modulePath || context.language || context.family) {
      state.contextLabels.set(context.identity, meta.label);
    }
  }
  function hasUnresolvedContexts() {
    return (state.contexts || []).some(context => context.identity !== state.snapshot && !state.contextKinds?.has(context.identity));
  }
  function applyContextLabel(identity, labelOrMeta) {
    if (!identity || !labelOrMeta) return;
    const meta = typeof labelOrMeta === 'string'
      ? { ...formatContext(contextRecord(identity)), label: labelOrMeta }
      : labelOrMeta;
    if (!meta?.label) return;
    state.contextLabels?.set(identity, meta.label);
    const mainOption = [...$('context').options].find(n => n.value === identity);
    if (mainOption) applyOptionMeta(mainOption, meta, false);
    for (const select of document.querySelectorAll('select[aria-label="Graph context"]')) {
      const option = [...select.options].find(n => n.value === identity);
      if (option) applyOptionMeta(option, meta, true);
    }
    for (const select of document.querySelectorAll('select[aria-label="Spring context"]')) {
      const option = [...select.options].find(n => n.value === identity);
      if (option) applyOptionMeta(option, meta, false);
    }
    notifyContextsUpdated();
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
        const resolvedPath = metadata?.modulePath || (build?.module ? state.moduleLabels.get(build.module) : null);
        const normalizedEvidence = evidence
          ? { ...evidence, metadata: { ...(metadata || {}), ...(resolvedPath ? { modulePath: resolvedPath } : {}) } }
          : null;
        const meta = formatContext(contextRecord(identity), normalizedEvidence);
        if (meta?.label) applyContextLabel(identity, meta);
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
    const publication = state.publication, graph = state.graph;
    if (!publication || !graph || state.contextOptionsLoading === publication) return;
    state.contextOptionsLoading = publication;
    try {
      if (!state.discoveryEnriched && Boolean(state.capabilities?.contextMetadataEnabled) && hasUnresolvedContexts()) {
        state.discoveryEnriched = true;
        try {
          const discovery = await api('/graphs/' + graph, undefined, publication.signal, 'GET', 60000);
          if (state.publication !== publication || publication.signal.aborted) return;
          if (Array.isArray(discovery?.reports)) {
            state.reportIds = [...new Set([...state.reportIds, ...discovery.reports])];
            state.reportIndexLoaded = true;
          }
          if (Array.isArray(discovery?.contexts) && discovery.contexts.length) {
            const byId = new Map(discovery.contexts.map(c => [c.identity, c]));
            let updated = false;
            state.contexts = state.contexts.map(existing => {
              const enriched = byId.get(existing.identity);
              return enriched ? { ...existing, ...enriched } : existing;
            });
            for (const context of state.contexts) {
              const enriched = byId.get(context.identity);
              if (!enriched) continue;
              const meta = formatContext(context);
              recordContextMetadata(context, meta);
              const mainOption = [...$('context').options].find(n => n.value === context.identity);
              if (mainOption) applyOptionMeta(mainOption, meta, false);
              for (const select of document.querySelectorAll('select[aria-label="Graph context"]')) {
                const option = [...select.options].find(n => n.value === context.identity);
                if (option) applyOptionMeta(option, meta, true);
              }
              for (const select of document.querySelectorAll('select[aria-label="Spring context"]')) {
                const option = [...select.options].find(n => n.value === context.identity);
                if (option) applyOptionMeta(option, meta, false);
              }
              updated = true;
            }
            if (updated) notifyContextsUpdated();
          }
        } catch (_) {
          // Fall back cleanly to per-context provenance lookup if discovery is unavailable.
        }
      }
      const contexts = state.contexts.filter(context => context.identity !== state.snapshot && !state.contextKinds.has(context.identity));
      for (let i = 0; i < contexts.length && !publication.signal.aborted; i += 2) {
        await Promise.all(contexts.slice(i, i + 2).map(context => labelContext(context.identity)));
      }
    } finally {
      if (state.contextOptionsLoading === publication) state.contextOptionsLoading = null;
    }
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
    if (window.EvoLucian?.createSkeletonCard) return window.EvoLucian.createSkeletonCard(rows);
    const wrap = el('div', 'evo-skeleton-stack');
    wrap.setAttribute('aria-hidden', 'true');
    wrap.append(el('i', 'evo-skeleton-bone is-title'));
    for (let i = 0; i < rows; i++) wrap.append(el('i', 'evo-skeleton-bone ' + (i === rows - 1 ? 'is-mid' : 'is-wide')));
    return wrap;
  }
  function renderSourceCodeBlock(data, highlightLine, onPage) {
    const shell = el('div', 'source-viewer-shell');
    const bar = el('div', 'source-viewer-bar');
    const ghEnt = window.EvoGitHubEnterprise;
    const owners = Array.isArray(data.owners) && data.owners.length
      ? data.owners
      : (data.path ? ghEnt?.ownersForPath?.(state.graph, data.path) : []);
    const ownerPills = ghEnt?.makeOwnerPills?.(owners);
    const meta = append(el('div'),
      append(el('div', 'gh-owner-row'), el('div', 'source-viewer-path', data.path || 'Captured file'), ownerPills),
      el('div', 'small muted', [
        data.language || 'text',
        data.startLine && data.endLine ? `Lines ${data.startLine}–${data.endLine} of ${data.totalLines || data.endLine}` : null,
        data.digest ? M.shortDigest(data.digest) : null
      ].filter(Boolean).join(' · '))
    );
    const barActions = el('div', 'remedy-patch-actions');
    const permUrl = ghEnt?.permalinkFor?.(data.path, data.startLine || highlightLine || 1, data.endLine, data.permalink);
    const permLink = ghEnt?.makePermalinkAnchor?.(permUrl, 'View on GitHub ↗', 'gh-permalink-link');
    if (permLink) barActions.append(permLink);
    const copyBtn = btn('Copy code', async () => {
      try {
        await navigator.clipboard?.writeText(data.content || '');
        window.EvoCopyFx?.flash?.(copyBtn, 'Copied', 'Copy code');
        window.EvoToast?.push({ kind: 'ok', title: 'Source copied', detail: data.path });
      } catch (_) {}
    }, 'button subtle u008-cp-btn');
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

  /* Lucian UX/UI Prompt #472, #098 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#472 */
  function renderScoreShowcase({ mode = 'dimensions', rawScore, displayText, subtitle, statusLabel, statusTone, breakdownRows = [] }) {
    const num = rawScore == null ? NaN : Number(rawScore);
    const numeric = Number.isFinite(num) ? Math.max(0, Math.min(100, num)) : null;
    const tone = statusTone || (numeric == null ? 'muted' : numeric >= 80 ? 'good' : numeric >= 50 ? 'warn' : 'danger');
    const bandLabel = statusLabel || (numeric == null ? 'Unavailable' : numeric >= 80 ? 'Strong' : numeric >= 50 ? 'Moderate' : 'At risk');
    const isAdditive = mode === 'additive' && breakdownRows.length > 0;
    const totalWeight = isAdditive ? breakdownRows.reduce((sum, r) => sum + Math.max(0, Number(r.weight) || 0), 0) : 0;
    const hasWeights = isAdditive && totalWeight > 0;
    const rm = Boolean(window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);
    const ns = 'http://www.w3.org/2000/svg';
    const svgEl = (tag, attrs = {}) => {
      const n = document.createElementNS(ns, tag);
      for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, String(v));
      return n;
    };

    const wrap = el('div', 'evo-score-wrap');
    const stage = el('div', 'ux evo-score-stage' + (breakdownRows.length ? '' : ' is-compact'));
    stage.dataset.tone = tone;
    stage.dataset.mode = hasWeights ? 'additive' : 'dimensions';

    // ── Left: Restrained Single-Accent Precision Dial (136px, viewBox 0 0 144 144) ──
    const dialWrap = el('div', 'evo-dial');
    const svg = svgEl('svg', { class: 'evo-dial-svg', viewBox: '0 0 144 144', 'aria-hidden': 'true' });
    const cx = 72, cy = 72, rad = 54, C = 2 * Math.PI * rad;

    const bezel = svgEl('g', { class: 'evo-dial-bezel' });
    for (let i = 0; i < 24; i++) {
      const a = -Math.PI / 2 + (i / 24) * Math.PI * 2;
      const major = i % 6 === 0;
      bezel.append(svgEl('line', {
        x1: (cx + Math.cos(a) * 64.5).toFixed(2),
        y1: (cy + Math.sin(a) * 64.5).toFixed(2),
        x2: (cx + Math.cos(a) * (major ? 70 : 67.2)).toFixed(2),
        y2: (cy + Math.sin(a) * (major ? 70 : 67.2)).toFixed(2),
        class: major ? 'major' : ''
      }));
    }
    svg.append(bezel);

    const dialSegs = [];
    const animArcs = [];

    if (hasWeights && numeric != null) {
      const count = breakdownRows.length;
      const gapRad = count > 1 ? 3.2 / rad : 0;
      let cursor = 0;
      breakdownRows.forEach((row, idx) => {
        const w = Math.max(0, Number(row.weight) || 0);
        const frac = w / totalWeight;
        const f0 = cursor;
        const f1 = cursor + frac;
        cursor = f1;
        const observed = row.state === 'OBSERVED';
        const g = svgEl('g', { class: 'evo-dial-seg', 'data-seg-idx': String(idx), 'data-step': String(idx % 4), 'data-state': observed ? 'observed' : 'gap' });

        if (count === 1) {
          const slot = svgEl('circle', { class: 'evo-dial-slot', cx, cy, r: rad });
          g.append(slot);
          if (observed) {
            const arc = svgEl('circle', {
              class: 'evo-dial-arc', cx, cy, r: rad,
              transform: `rotate(-90 ${cx} ${cy})`,
              'stroke-dasharray': rm ? `${C.toFixed(2)} ${C.toFixed(2)}` : `0 ${C.toFixed(2)}`
            });
            g.append(arc);
            animArcs.push({ el: arc, len: C, total: C, delay: 100 });
          }
          g.append(svgEl('circle', { class: 'evo-dial-hit', cx, cy, r: rad }));
        } else {
          const a0 = -Math.PI / 2 + f0 * Math.PI * 2 + gapRad / 2;
          const a1 = -Math.PI / 2 + f1 * Math.PI * 2 - gapRad / 2;
          const span = Math.max(0.02, a1 - a0);
          const largeArc = span > Math.PI ? 1 : 0;
          const x0 = cx + Math.cos(a0) * rad, y0 = cy + Math.sin(a0) * rad;
          const x1 = cx + Math.cos(a1) * rad, y1 = cy + Math.sin(a1) * rad;
          const d = `M ${x0.toFixed(2)} ${y0.toFixed(2)} A ${rad} ${rad} 0 ${largeArc} 1 ${x1.toFixed(2)} ${y1.toFixed(2)}`;
          const segLen = span * rad;

          g.append(svgEl('path', { class: 'evo-dial-slot', d }));
          if (observed) {
            const arc = svgEl('path', {
              class: 'evo-dial-arc', d,
              'stroke-dasharray': rm ? `${segLen.toFixed(2)} ${(segLen + 2).toFixed(2)}` : `0 ${(segLen + 2).toFixed(2)}`
            });
            g.append(arc);
            animArcs.push({ el: arc, len: segLen, total: segLen + 2, delay: idx * 90 + 110 });
          }
          g.append(svgEl('path', { class: 'evo-dial-hit', d }));
        }
        svg.append(g);
        dialSegs.push(g);
      });
    } else {
      const track = svgEl('circle', {
        class: 'evo-dial-track' + (numeric == null ? ' is-withheld' : ''),
        cx, cy, r: rad
      });
      svg.append(track);
      if (numeric != null && numeric > 0) {
        const targetLen = (numeric / 100) * C;
        const arc = svgEl('circle', {
          class: 'evo-dial-single-arc', cx, cy, r: rad,
          transform: `rotate(-90 ${cx} ${cy})`,
          'stroke-dasharray': rm ? `${targetLen.toFixed(2)} ${C.toFixed(2)}` : `0 ${C.toFixed(2)}`
        });
        svg.append(arc);
        animArcs.push({ el: arc, len: targetLen, total: C, delay: 80 });
      }
    }

    const center = el('div', 'evo-dial-center');
    center.setAttribute('aria-hidden', 'true');
    const centerText = numeric == null ? '—'
      : Number.isInteger(numeric) ? String(numeric) : String(Math.round(numeric * 10) / 10);
    const centerSubText = numeric != null ? '/ 100' : (bandLabel === 'Withheld' ? 'Withheld' : 'N/A');
    center.append(el('span', 'evo-dial-val', centerText), el('span', 'evo-dial-sub', centerSubText));
    dialWrap.append(svg, center);

    // ── Right Top: Lucian #098 Odometer Readout + Editorial Qualification Pill ──
    const summary = el('div', 'evo-score-summary');
    const headRow = el('div', 'score-heading');
    const scoreText = displayText || 'Unavailable';
    const exactStrong = el('strong', 'score', scoreText);
    const odometerMatch = numeric != null && /^\s*(\d+(?:\.\d+)?)\s*\/\s*100\s*$/.exec(scoreText);

    if (odometerMatch) {
      exactStrong.classList.add('sr-only');
      const numStr = odometerMatch[1];
      const odo = el('div', 'u098-num');
      odo.setAttribute('aria-hidden', 'true');
      const digitCols = [];
      [...numStr].forEach((ch, idx) => {
        if (ch >= '0' && ch <= '9') {
          const col = el('span', 'u098-col');
          col.style.setProperty('--i', String(idx));
          col.style.setProperty('--d', '0');
          for (let d = 0; d <= 9; d++) col.append(el('span', '', String(d)));
          digitCols.push({ col, target: Number(ch) });
          odo.append(col);
        } else {
          odo.append(el('span', 'u098-dot', ch));
        }
      });
      odo.append(el('span', 'u098-unit', '/ 100'));
      headRow.append(odo, exactStrong);
      if (rm) {
        digitCols.forEach(dc => dc.col.style.setProperty('--d', String(dc.target)));
      } else {
        requestAnimationFrame(() => requestAnimationFrame(() => {
          digitCols.forEach(dc => dc.col.style.setProperty('--d', String(dc.target)));
        }));
      }
    } else {
      headRow.append(exactStrong);
    }

    const pill = el('span', 'evo-score-pill', bandLabel);
    pill.dataset.tone = tone;
    headRow.append(pill);
    summary.append(headRow);
    if (subtitle) summary.append(el('p', 'small muted evo-score-sub', subtitle));

    // ── Shared 3-way hover/focus linkage (Dial Arc <-> #472 Ribbon <-> Audit Ledger) ──
    const segButtons = [];
    const rowNodes = [];
    let ribbonRoot = null, siEl = null, siDot = null, siLabel = null, siVal = null, siPct = null;
    let activeIdx = -1;

    const highlight = idx => {
      const next = hasWeights && idx >= 0 && idx < breakdownRows.length ? idx : -1;
      if (next === activeIdx) return;
      const prev = activeIdx;
      activeIdx = next;
      const active = next >= 0;
      stage.classList.toggle('has', active);
      dialSegs.forEach((g, k) => g.classList.toggle('is-on', active && k === next));
      segButtons.forEach((b, k) => b.classList.toggle('is-on', active && k === next));
      rowNodes.forEach((rn, k) => rn.classList.toggle('is-on', active && k === next));
      if (ribbonRoot) ribbonRoot.classList.toggle('is-hl', active);
      if (active && siEl) {
        const row = breakdownRows[next];
        const observed = numeric != null && row.state === 'OBSERVED';
        const pct = Math.round(((Math.max(0, Number(row.weight) || 0)) / totalWeight) * 100);
        siDot.classList.toggle('is-gap', !observed);
        siDot.dataset.step = String(next % 4);
        siDot.style.background = observed ? `var(--evo-step-${next % 4})` : '';
        siLabel.textContent = row.label;
        siVal.textContent = row.valueText;
        siPct.textContent = '· ' + pct + '%';
        if (prev >= 0 && !rm) {
          siEl.classList.remove('is-tick');
          void siEl.offsetWidth;
          siEl.classList.add('is-tick');
        }
      }
    };

    // ── Right Middle (Additive Repository Score ONLY): Lucian #472 Proportional Composition Ribbon ──
    if (hasWeights) {
      ribbonRoot = el('div', 'evo-u472 u472');
      const hd = el('div', 'u472-hd');
      const observedCount = breakdownRows.filter(r => r.state === 'OBSERVED').length;
      const earnedPts = breakdownRows.reduce((sum, r) => sum + (r.state === 'OBSERVED' ? Math.max(0, Number(r.weight) || 0) : 0), 0);
      const summaryLine = el('p', 'u472-us');
      if (numeric == null) {
        summaryLine.append(el('b', '', String(breakdownRows.length) + ' checks'), ` · ${totalWeight} pts weighted`);
      } else {
        summaryLine.append(
          el('b', '', `${observedCount} of ${breakdownRows.length}`),
          ` observed · ${earnedPts === totalWeight ? totalWeight + ' pts' : earnedPts + ' / ' + totalWeight + ' pts'}`
        );
      }

      siEl = el('p', 'u472-si');
      siEl.setAttribute('aria-hidden', 'true');
      siDot = el('i');
      siLabel = el('span', 'u472-tl');
      siVal = el('b', 'u472-tv');
      siPct = el('em', 'u472-tp');
      siEl.append(siDot, siLabel, siVal, siPct);

      const sw = append(el('div', 'u472-sw'), summaryLine, siEl);
      hd.append(el('span', 'u472-lbl', 'Weight composition'), sw);

      const bar = el('div', 'u472-bar');
      bar.setAttribute('role', 'group');
      bar.setAttribute('aria-label', 'Proportional check weight composition');

      breakdownRows.forEach((row, idx) => {
        const w = Math.max(0, Number(row.weight) || 0);
        const pctFloat = (w / totalWeight) * 100;
        const pctRounded = Math.round(pctFloat);
        const observed = numeric != null && row.state === 'OBSERVED';
        const seg = document.createElement('button');
        seg.type = 'button';
        seg.className = 'u472-s';
        seg.dataset.step = String(idx % 4);
        seg.dataset.state = observed ? 'observed' : 'gap';
        seg.style.setProperty('--u472-i', String(idx));
        seg.style.setProperty('--u472-w', pctFloat.toFixed(4) + '%');
        seg.setAttribute('aria-label', `${row.label}: ${row.valueText} (${pctRounded}% weight)`);
        seg.addEventListener('pointerenter', () => highlight(idx));
        seg.addEventListener('focus', () => highlight(idx));
        seg.addEventListener('blur', () => { if (!stage.matches(':hover')) highlight(-1); });
        seg.addEventListener('click', () => highlight(activeIdx === idx ? -1 : idx));
        segButtons.push(seg);
        bar.append(seg);
      });

      bar.addEventListener('pointerleave', () => {
        if (!bar.contains(document.activeElement)) highlight(-1);
      });

      ribbonRoot.append(hd, bar);
      summary.append(ribbonRoot);

      if (rm) {
        ribbonRoot.classList.add('is-in');
      } else {
        requestAnimationFrame(() => requestAnimationFrame(() => {
          ribbonRoot.classList.add('is-in');
        }));
      }
    }

    // ── Right Bottom: Clean Editorial Audit Ledger (Zero full-width bars) ──
    if (breakdownRows.length) {
      const ledger = el('div', 'evo-score-ledger');
      breakdownRows.forEach((row, idx) => {
        if (hasWeights) {
          const observed = numeric != null && row.state === 'OBSERVED';
          const absent = row.state === 'ABSENT';
          const rowEl = el('div', 'evo-ledger-row');
          rowEl.dataset.step = String(idx % 4);
          rowEl.dataset.state = observed ? 'observed' : 'gap';

          const main = el('div', 'evo-ledger-main');
          const head = el('div', 'evo-ledger-head');
          const swatch = el('span', 'evo-ledger-swatch');
          swatch.setAttribute('aria-hidden', 'true');
          const glyph = el('span', 'evo-ledger-status', observed ? '✓' : absent ? '○' : '—');
          glyph.dataset.state = observed ? 'observed' : absent ? 'absent' : 'unknown';
          glyph.setAttribute('aria-hidden', 'true');
          head.append(swatch, glyph, el('span', 'evo-ledger-name', row.label));
          main.append(head);

          if (row.note || row.actionNode) {
            const meta = el('div', 'evo-ledger-meta');
            if (row.note) meta.append(el('span', 'evo-ledger-note', row.note));
            if (row.actionNode) meta.append(row.actionNode);
            main.append(meta);
          }

          const val = el('strong', 'evo-ledger-val' + (observed ? '' : ' is-muted'), row.valueText);
          rowEl.append(main, val);

          rowEl.addEventListener('pointerenter', () => highlight(idx));
          rowEl.addEventListener('focusin', () => highlight(idx));
          rowEl.addEventListener('pointerleave', () => {
            if (!rowEl.contains(document.activeElement)) highlight(-1);
          });
          rowEl.addEventListener('focusout', e => {
            if (!stage.contains(e.relatedTarget)) highlight(-1);
          });

          rowNodes.push(rowEl);
          ledger.append(rowEl);
        } else {
          // Independent Architecture Assessment dimension row: understated 44px x 4px micro-meter + tabular score
          const rowEl = el('div', 'evo-ledger-row is-dim-row');
          const hasRatio = row.ratio != null && Number.isFinite(row.ratio);
          const dimTone = !hasRatio ? 'muted' : (row.tone || 'good');

          const head = el('div', 'evo-ledger-head');
          const dot = el('span', 'evo-dim-dot');
          dot.dataset.tone = dimTone;
          dot.setAttribute('aria-hidden', 'true');
          head.append(dot);
          if (typeof row.onInspect === 'function') {
            head.append(btn(row.label, row.onInspect, 'text-button evo-dim-btn'));
          } else {
            head.append(el('span', 'evo-ledger-name', row.label));
          }

          const end = el('div', 'evo-dim-end');
          const meter = el('span', 'evo-micro-meter' + (hasRatio ? '' : ' is-empty'));
          meter.dataset.tone = dimTone;
          meter.setAttribute('aria-hidden', 'true');
          if (hasRatio) {
            const fill = el('i');
            fill.style.width = (Math.max(0, Math.min(1, row.ratio)) * 100).toFixed(1) + '%';
            meter.append(fill);
          }
          const val = el('strong', 'evo-ledger-val' + (hasRatio ? '' : ' is-muted'), row.valueText);
          end.append(meter, val);

          rowEl.append(head, end);
          rowNodes.push(rowEl);
          ledger.append(rowEl);
        }
      });
      summary.append(ledger);
    }

    if (hasWeights && dialSegs.length) {
      dialSegs.forEach((g, idx) => {
        g.addEventListener('pointerenter', () => highlight(idx));
        g.addEventListener('pointerleave', () => highlight(-1));
      });
    }

    stage.append(dialWrap, summary);
    wrap.append(stage);

    // Smoothly sweep dial arc(s) from 0 to target (instant when prefers-reduced-motion)
    if (!rm && animArcs.length) {
      const t0 = performance.now();
      const dur = 820;
      (function frame(now) {
        if (!stage.isConnected && now - t0 > 1400) return;
        let done = true;
        animArcs.forEach(a => {
          const t = Math.max(0, Math.min(1, (now - t0 - a.delay) / dur));
          if (t < 1) done = false;
          const e = 1 - Math.pow(1 - t, 3);
          a.el.setAttribute('stroke-dasharray', `${(a.len * e).toFixed(2)} ${a.total.toFixed(2)}`);
        });
        if (!done) requestAnimationFrame(frame);
      })(t0);
    }

    return wrap;
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

  function coverageCard(env, result) {
    const section = card('Analysis coverage');
    section.classList.add('job-coverage');
    const line = append(el('p', 'coverage-line'),
      badge(result.completion),
      el('span', '', result.gapCount.toLocaleString() + ' recorded gaps across the run'));
    section.append(line);
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
  async function recoverSupersededPreview(error) {
    const jobId = state.job?.id;
    const generation = state.pollGeneration;
    if (jobId) {
      const refreshed = await api('/analysis/jobs/' + jobId, undefined, undefined, 'GET', 10000).catch(() => null);
      if (refreshed && selectedJob(jobId, generation)) {
        state.job = refreshed;
        if (refreshed.result?.graph && refreshed.result.graph !== state.graph) {
          await loadGraph(refreshed, jobId, generation, { preserveInteraction: true, promotedFromPreview: true });
          return true;
        }
        const nextPreviewId = error?.supersededBy || refreshed.previewGraphId;
        if (nextPreviewId && nextPreviewId !== state.graph) {
          await loadPreviewGraph({ graphId: nextPreviewId, truthTier: refreshed.publicationStage || 'PARTIAL_CONTEXTS', ...(refreshed.preview || {}) }, jobId, generation);
          return true;
        }
      }
    } else if (error?.supersededBy && error.supersededBy !== state.graph) {
      await loadPreviewGraph({ graphId: error.supersededBy, truthTier: 'PARTIAL_CONTEXTS' }, state.job?.id, generation);
      return true;
    }
    return false;
  }
  async function loadTab(tab, opts = {}) {
    const panel = $('panel-' + tab);
    if (!panel) return;
    invalidate(Boolean(opts.keepInspector)); state.tab = tab; const epoch = state.epoch;
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
    catch (error) {
      if (epoch !== state.epoch || error.name === 'AbortError') return;
      if ((error.status === 410 || error.code === 'PREVIEW_SUPERSEDED') && !opts._recoveredPreview) {
        const recovered = await recoverSupersededPreview(error);
        if (recovered) return;
      }
      panel.setAttribute('aria-busy', 'false');
      if (error.status === 409 && error.code === 'PRELIMINARY_GRAPH_PENDING') {
        panel.replaceChildren(
          empty('Deep semantic analysis in progress', M.apiError('PRELIMINARY_GRAPH_PENDING')),
          append(el('div', 'toolbar'),
            btn('View live progress', () => { const d = $('run-details'); if (d) d.open = true; }, 'button subtle'),
            btn('Retry now', () => loadTab(tab), 'button primary'))
        );
        $('view-status').textContent = 'Waiting for deep semantic analysis to complete.';
        return;
      }
      panel.replaceChildren(empty('Unable to load this view', error.message), btn('Retry', () => loadTab(tab)));
      $('view-status').textContent = 'Request failed. Retry to read this analysis.';
    }
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
      const includeTechnical = kind === 'CAPABILITY GAP';
      const fields = el('dl', 'data-fields');
      for (const [key, val] of Object.entries(value || {})) if (val != null && typeof val !== 'object' && (includeTechnical || !technicalField(key, val)) && !['query', 'identity', 'id', 'setup', 'graph', 'snapshot', 'acquisition', 'sourceReport', 'continuation'].includes(key)) {
        const dd = el('dd');
        if (typeof val === 'string' && /^sha256:[0-9a-f]{16,}$/i.test(val)) dd.append(digestChip(val));
        else dd.textContent = String(val);
        append(fields, append(el('div', 'data-field'), el('dt', '', key.replace(/([A-Z])/g, ' $1')), dd));
      }
      body.append(fields);
      if (Array.isArray(value?.items)) {
        const items = includeTechnical ? value.items : value.items.filter(item => item.kind !== 'CAPABILITY_GAP' && !item.gapIdentity);
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
            const detail = append(el('details'), el('summary', '', 'Source identities & coordinates')); renderFields(detail, item, 0, false, includeTechnical); section.append(detail);
          } else renderFields(section, item.metadata || item, 0, false, includeTechnical);
          body.append(section);
        }
        if (!items.length) body.append(empty('No records returned', ''));
        if (value.continuation && typeof loader === 'function') body.append(btn('Load more evidence', () => inspect(title, kind, loader, actions, value.items, value.continuation)));
      } else renderFields(body, value, 0, true, includeTechnical);
      finish();
    } catch (error) { if (!controller.signal.aborted) $('inspector-body').replaceChildren(empty('Details unavailable', error.message)); }
  }
  function technicalField(key, value) {
    return /gap|limitation|diagnostic|^bounds$|^confidence$|^evidenceStatus$|^isTruncated$/i.test(key)
      || /status$/i.test(key) && ['COMPLETE', 'PARTIAL'].includes(value);
  }
  function renderFields(parent, value, depth, objectsOnly = false, includeTechnical = false) {
    if (!value || typeof value !== 'object') return;
    const fields = el('dl', 'data-fields');
    for (const [key, val] of Object.entries(value)) {
      if (val == null || (!includeTechnical && technicalField(key, val)) || objectsOnly && typeof val !== 'object') continue;
      if (typeof val !== 'object') {
        const resolved = ['source', 'target'].includes(key) ? state.labels.get(val) || val : val;
        const dd = el('dd');
        if (typeof resolved === 'string' && /^sha256:[0-9a-f]{16,}$/i.test(resolved)) dd.append(digestChip(resolved));
        else dd.textContent = String(resolved);
        append(fields, append(el('div', 'data-field'), el('dt', '', key.replace(/([A-Z])/g, ' $1')), dd));
      } else if (depth < 3 && Object.keys(val).length) {
        const detail = append(el('details'), el('summary', '', key.replace(/([A-Z])/g, ' $1')));
        if (['entity', 'span', 'sourceSpan', 'declaration', 'path', 'dimensions', 'record', 'humanExplanation'].includes(key)) detail.open = true;
        renderFields(detail, val, depth + 1, false, includeTechnical); fields.append(detail);
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
    const entityHint = M.label(item) || item.identity || sourcePath;
    const p = inspect(M.label(item), item.kind || 'ENTITY', (signal, cursor) => query('explain', { identity: item.identity }, signal, {}, cursor), actions);
    void p.then(() => {
      if (state.inspector && !state.inspector.signal.aborted && window.EvoCommunityAdvisory?.attachInspectorAdvisoryLinks) {
        void window.EvoCommunityAdvisory.attachInspectorAdvisoryLinks($('inspector-body'), api, env.graph, entityHint, state.inspector.signal);
      }
    });
    return p;
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
          const cycleText = el('p', 'small', `This finding represents a circular dependency cycle across ${f.bundle.path.length} classes: ${f.bundle.path.map(id => state.labels.get(id) || M.shortDigest(id)).join(' → ')}.`);
          resolveLabels(f.bundle.path, env).then(() => {
            if (!env.signal.aborted && cycleText.isConnected) {
              cycleText.textContent = `This finding represents a circular dependency cycle across ${f.bundle.path.length} classes: ${f.bundle.path.map(id => state.labels.get(id) || M.shortDigest(id)).join(' → ')}.`;
            }
          }).catch(() => {});
          append(cycleCard,
            el('strong', '', 'Circular Dependency Structure'),
            cycleText,
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
        } else if (line.startsWith('\\ No newline at end of file')) {
          lineEl.className += ' diff-meta';
          lineEl.textContent = line;
        } else {
          if (oldLine || newLine) lineEl.append(el('span', 'diff-line-num', String(newLine++ || oldLine)));
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
          window.EvoCopyFx?.flash?.(copyBtn, 'Copied!', 'Copy Patch');
          window.EvoToast?.push({ kind: 'ok', title: 'Patch copied', detail: targetList });
        } catch (_) { }
      }, 'button subtle u008-cp-btn');

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
    } else {
      const msg = el('div', 'notice');
      append(msg,
        el('strong', '', 'Repair Proposal Withheld'),
        el('p', 'small', repairReasons[proposal.reason] || proposal.reason || 'Select an applicable recipe above or configure an AI repair model for this finding.'),
        el('p', 'small muted', 'Applicable recipes: ' + (applicableList.map(r => r.id).join(', ') || 'none')
          + ' · Registered total: ' + rankedRecipes.length)
      );
      propBody.append(msg);
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
    const node = card('Repository Hygiene (4 File Checks)', 'Baseline file-presence checks: README, Tests, CI, Build — separate from Software Architecture Health.');
    node.classList.add('evo-hygiene-card');
    if (!editable) node.classList.add('overview-score');
    const heading = node.querySelector('.card-heading');
    if (heading) heading.append(el('span', 'evo-hygiene-badge', 'File checks'));
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
        const withheld = score.status === 'WITHHELD';
        const value = score.score == null
          ? withheld || score.lower == null || score.upper == null ? 'Unavailable' : (score.lower === score.upper ? score.lower : score.lower + '–' + score.upper) + ' / 100'
          : score.score + ' / 100';
        const numericRing = withheld ? null : (score.score ?? (score.lower != null && score.upper != null ? (score.lower + score.upper) / 2 : null));
        const stateText = { OBSERVED: 'Observed', ABSENT: 'No match', UNKNOWN: '—', NOT_APPLICABLE: 'Not applicable' };
        const breakdownRows = (score.checks || []).map(check => {
          const label = fields.find(([id]) => id === check.name)?.[1] ?? check.name;
          const contribution = check.state === 'OBSERVED' ? check.weight + ' / ' + check.weight
            : check.state === 'ABSENT' ? '0 / ' + check.weight : (stateText[check.state] || '—');
          const actionNode = check.evidence
            ? btn(check.evidence.path, () => inspect('Scoring evidence', 'CAPTURED FILE',
              { snapshot: score.snapshot, check: check.criterion, path: check.evidence.path, evidence: check.evidence.identity }), 'text-button evo-file-pill')
            : null;
          return {
            label,
            weight: Number(check.weight) || 0,
            state: check.state,
            valueText: contribution,
            note: check.criterion,
            actionNode
          };
        });
        result.replaceChildren(renderScoreShowcase({
          mode: 'additive',
          rawScore: numericRing,
          displayText: value,
          subtitle: withheld ? 'Evidence insufficient for a deterministic repository score' : 'File-based engineering readiness checks',
          statusLabel: withheld ? 'Withheld' : undefined,
          statusTone: withheld || numericRing == null ? 'muted' : undefined,
          breakdownRows
        }));
      } catch (error) {
        if (env.signal.aborted || current !== version) return;
        if (save) feedback.textContent = error.code === 'INVALID_REQUEST' ? 'Use a path inside the repository, such as qa/smoke.sh.' : error.message;
        else result.replaceChildren(el('p', '', error.message), btn('Retry score', () => read()));
      }
    }
    read();
    return node;
  }

  function fetchArchitectureScoreV2(env, policy = null) {
    const key = JSON.stringify([env.graph, env.context, policy || null]);
    const existing = state.scoreV2Cache.get(key);
    if (existing) return existing;
    const body = { context: env.context, ...(policy ? { policy } : {}) };
    const promise = api('/graphs/' + env.graph + '/architecture-score', body, state.publication?.signal, 'POST', 60000);
    state.scoreV2Cache.set(key, promise);
    promise.catch(() => { if (state.scoreV2Cache.get(key) === promise) state.scoreV2Cache.delete(key); });
    return promise;
  }

  function architectureScoreV2Card(env, { detailed = false } = {}) {
    const node = card('Architecture Health v2', '7-dimension structural, modular, Spring, polyglot & policy health — strictly separated from Analysis Confidence.');
    node.classList.add('evo-v2-card');
    if (M.isPreliminaryTier?.(state.truthTier)) {
      const info = M.truthTierInfo(state.truthTier, state.previewMeta);
      node.append(
        append(el('div', 'notice'),
          el('strong', '', 'Architecture Health v2 pending deep semantic analysis'),
          el('p', 'small', info.detail)),
        append(el('div', 'toolbar'),
          btn('Explore Dependency Map', () => loadTab('graph'), 'button primary'),
          btn('Browse RepoMap & Source', () => loadTab('repomap'), 'button subtle'),
          btn('Browse Structure', () => loadTab('structure'), 'button subtle'))
      );
      return node;
    }
    const container = el('div');
    container.setAttribute('aria-live', 'polite');
    container.append(skeleton(3), el('p', 'small muted', 'Computing 7-dimension Architecture Health v2…'));
    node.append(container);

    const openWitness = async witness => {
      if (!witness) return;
      if (witness.identity && witness.kind && ['TYPE', 'PACKAGE', 'MODULE', 'BEAN_DEFINITION_CANDIDATE'].includes(witness.kind)) {
        state.graphFocus = { seed: witness.identity, scope: witness.kind === 'BEAN_DEFINITION_CANDIDATE' ? 'SPRING_COMPONENT' : witness.kind, context: defaultDependencyContext(env) };
        return loadTab('graph');
      }
      return inspect(witness.label || witness.identity || 'Evidence witness', 'SCORE V2 WITNESS', witness, [
        ...(witness.identity ? [{
          label: 'Focus in dependency map',
          run: () => {
            state.graphFocus = { seed: witness.identity, scope: 'TYPE', context: defaultDependencyContext(env) };
            closeInspector();
            return loadTab('graph');
          }
        }] : [])
      ]);
    };

    const renderV2 = v2 => {
      const rangeText = M.formatHealthRange(v2);
      const overallTone = M.v2OverallTone(v2);
      const conf = M.confidenceBadge(v2.confidence);
      const numericScore = v2.score != null && v2.status !== 'WITHHELD' ? Number(v2.score) : null;
      const hasInterval = Number(v2.uncertaintyDelta ?? 0) > 0 && v2.conservativeLowerBound != null && numericScore != null && Number(v2.conservativeLowerBound) < numericScore - 0.05;

      const showcase = renderScoreShowcase({
        mode: 'dimensions',
        rawScore: numericScore,
        displayText: rangeText,
        subtitle: [
          v2.scopeMode === 'WHOLE_REPOSITORY' ? 'Whole-repository composite' : 'Selected context',
          v2.formula?.id ? `${v2.formula.id}@${v2.formula.version}` : 'v2.0',
          v2.calibration === 'EMPIRICALLY_GROUNDED_PROVISIONAL' ? 'Provisional calibration' : v2.calibration
        ].filter(Boolean).join(' · '),
        statusLabel: v2.healthBand || (numericScore == null ? 'Withheld' : undefined),
        statusTone: overallTone,
        breakdownRows: []
      });

      const leftCol = el('div');
      leftCol.append(showcase);
      if (hasInterval) {
        leftCol.append(el('span', 'evo-v2-range-badge',
          `Conservative lower bound ${Number(v2.conservativeLowerBound).toFixed(1)} · observed ${numericScore.toFixed(1)} (Δ${Number(v2.uncertaintyDelta).toFixed(1)})`));
      }

      // Companion Analysis Confidence panel (strictly separate from Health!)
      const confPanel = el('aside', 'evo-confidence-panel');
      confPanel.setAttribute('aria-label', 'Analysis confidence');
      const confPill = el('span', 'evo-score-pill', `${conf.icon} ${conf.label}`);
      confPill.dataset.tone = conf.tone;
      confPanel.append(
        append(el('div', 'evo-confidence-head'),
          el('span', 'evo-confidence-title', 'Analysis confidence'),
          confPill),
        append(el('div', 'evo-confidence-score-row'),
          el('strong', 'evo-confidence-num', conf.pctText),
          el('span', 'small muted', conf.evidenceTier ? conf.evidenceTier.replaceAll('_', ' ') : 'Evidence coverage'))
      );

      const c = v2.confidence || {};
      const signals = [
        ['Parse completeness', c.parseCompleteness],
        ['Internal edge completeness', c.internalEdgeCompleteness],
        ['Semantic resolution', c.overallSemanticResolution],
        ['Build completeness', c.buildCompleteness],
        ...(c.springSpaceDefiniteness != null ? [['Spring space definiteness', c.springSpaceDefiniteness]] : [])
      ];
      const signalGrid = el('div', 'evo-confidence-signals');
      for (const [labelText, ratioVal] of signals) {
        if (ratioVal == null || !Number.isFinite(Number(ratioVal))) continue;
        const pct = Math.max(0, Math.min(100, Math.round(Number(ratioVal) * 100)));
        const sTone = pct >= 85 ? 'good' : pct >= 60 ? 'warn' : 'danger';
        const track = el('span', 'evo-signal-track');
        const fill = el('i');
        fill.style.width = pct + '%';
        track.append(fill);
        const sRow = append(el('div', 'evo-signal-row'), el('span', '', labelText), track, el('span', 'evo-signal-val', pct + '%'));
        sRow.dataset.tone = sTone;
        signalGrid.append(sRow);
      }
      confPanel.append(signalGrid);
      const confFooter = el('div', 'toolbar');
      if (Number(c.openGapCount || 0) > 0) {
        confFooter.append(btn(`Inspect ${Number(c.openGapCount).toLocaleString()} open gaps →`, () => loadTab('coverage'), 'text-button'));
      }
      confFooter.append(btn('Raw v2 JSON', () => inspect('Architecture Health v2', 'ASSESSMENT V2', v2), 'text-button'));
      confPanel.append(confFooter);

      const hero = append(el('div', 'evo-v2-hero'), leftCol, confPanel);

      // 7 Interactive Architecture Dimensions with expandable Sub-Metrics & Calibration Tracks
      const dimsWrap = el('div', 'evo-v2-dims-list');
      for (const dim of v2.dimensions || []) {
        const dTone = M.v2DimensionTone(dim, conf.tier);
        const dNum = dim.score != null && Number.isFinite(Number(dim.score)) ? Number(dim.score) : null;
        const dDetails = el('details', 'evo-v2-dim-details');
        if (detailed && dNum != null && dNum < 70) dDetails.open = true;

        const dot = el('span', 'evo-dim-dot');
        dot.dataset.tone = dTone;
        dot.setAttribute('aria-hidden', 'true');
        const titleWrap = append(el('div', 'evo-v2-dim-title-wrap'),
          dot,
          el('span', 'evo-v2-dim-name', dim.displayName || dim.id),
          el('span', 'evo-v2-dim-meta', `${dim.iso25010Mapping || ''} · weight ${Number(dim.weight || 0)}%`));

        const end = el('div', 'evo-dim-end');
        const meter = el('span', 'evo-micro-meter' + (dNum == null ? ' is-empty' : ''));
        meter.dataset.tone = dTone;
        if (dNum != null) {
          const fill = el('i');
          fill.style.width = Math.max(0, Math.min(100, dNum)).toFixed(1) + '%';
          meter.append(fill);
        }
        const valStr = dNum != null
          ? (Number(dim.uncertaintyDelta ?? 0) > 0 && dim.conservativeLowerBound != null && Number(dim.conservativeLowerBound) < dNum - 0.05
              ? `${Number(dim.conservativeLowerBound).toFixed(0)}–${dNum.toFixed(0)}`
              : dNum.toFixed(1))
          : (dim.status === 'NOT_APPLICABLE' ? 'N/A' : 'Withheld');
        end.append(meter, el('strong', 'evo-ledger-val' + (dNum == null ? ' is-muted' : ''), valStr));

        dDetails.append(append(el('summary', 'evo-v2-dim-summary'), titleWrap, end));

        const drawer = el('div', 'evo-submetric-drawer');
        for (const sub of dim.subMetrics || []) {
          const sCard = el('article', 'evo-submetric-card');
          const subScore = sub.score != null ? Number(sub.score).toFixed(1) + ' / 100' : (sub.status === 'NOT_APPLICABLE' ? 'N/A' : 'Withheld');
          const rawStr = sub.rawValue != null ? `${sub.rawValue}${sub.unit ? ' ' + sub.unit : ''}` : '—';
          sCard.append(
            append(el('div', 'evo-submetric-head'),
              el('span', 'evo-submetric-title', sub.displayName || sub.id),
              el('span', 'evo-submetric-val', `${rawStr} · ${subScore}`))
          );
          if (sub.curve && sub.rawValue != null) {
            const pos = M.subMetricPosition(sub.rawValue, sub.curve);
            const track = append(el('div', 'evo-calibration-track'),
              el('span', 'evo-calibration-zone is-ideal'),
              el('span', 'evo-calibration-zone is-acceptable'),
              el('span', 'evo-calibration-zone is-critical'));
            const pin = el('span', 'evo-calibration-pin');
            pin.style.left = pos.percent + '%';
            pin.title = `${pos.zoneLabel} (${rawStr})`;
            track.append(pin);
            const legend = append(el('div', 'evo-calibration-legend'),
              el('span', '', `Ideal ${sub.curve.ideal}`),
              el('span', '', `Acceptable ${sub.curve.acceptable}`),
              el('span', '', `Poor ${sub.curve.poor}`),
              el('span', '', `Critical ${sub.curve.critical}`));
            sCard.append(track, legend);
          }
          if (sub.explanation) sCard.append(el('p', 'small muted', sub.explanation));
          if (sub.remediationHint) sCard.append(el('p', 'small', 'Tip: ' + sub.remediationHint));
          if (Array.isArray(sub.witnesses) && sub.witnesses.length) {
            const wList = el('div', 'evo-witness-list');
            for (const w of sub.witnesses.slice(0, 6)) {
              const chip = btn(w.label || M.shortDigest(w.identity), () => openWitness(w), 'evo-witness-chip');
              chip.title = [w.kind, w.label, w.detail].filter(Boolean).join(' — ');
              wList.append(chip);
            }
            sCard.append(wList);
          }
          drawer.append(sCard);
        }
        if (!drawer.childElementCount) {
          drawer.append(el('p', 'small muted', (dim.reasons || []).join(' · ') || 'No sub-metrics applicable in this context.'));
        }
        dDetails.append(drawer);
        dimsWrap.append(dDetails);
      }

      const pieces = [hero, dimsWrap];

      // Whole-Repository Context Breakdown (clickable module/service drilldown)
      if (Array.isArray(v2.contextBreakdown) && v2.contextBreakdown.length) {
        const ctxList = el('div', 'evo-context-score-list');
        for (const item of v2.contextBreakdown) {
          const scoreLabel = item.score != null ? Number(item.score).toFixed(1) : (item.status === 'WITHHELD' ? 'Withheld' : 'N/A');
          const cBtn = btn('', () => {
            if (state.contexts.some(ctx => ctx.identity === item.contextId)) {
              state.graphFocus = null; state.explorer = null; state.metricSubject = null;
              state.context = item.contextId;
              $('context').value = item.contextId;
              notifyContextsUpdated();
              return loadTab(state.tab);
            }
          }, 'evo-context-score-row');
          append(cBtn,
            append(el('div'),
              el('strong', '', item.label || item.contextId),
              el('p', 'small muted', `${item.kind} · ${Number(item.entityCount || 0).toLocaleString()} entities · ${item.confidenceTier}`)),
            el('strong', 'evo-ledger-val', scoreLabel));
          ctxList.append(cBtn);
        }
        pieces.push(el('h3', 'small', 'Context Score Breakdown (click to drill down)'), ctxList);
      }

      // Inferred Architecture Style & Layer Map + 1-click Customize as Policy YAML
      if (v2.inferredLayers && v2.inferredLayers.style) {
        const styleStrip = el('div', 'evo-layer-map-strip');
        const layerNames = Object.keys(v2.inferredLayers.layerPackages || {});
        append(styleStrip,
          append(el('div'),
            el('strong', '', `Inferred Architecture Style: ${v2.inferredLayers.style}`),
            el('p', 'small muted', layerNames.length ? `Detected layers: ${layerNames.join(' · ')}` : 'Inferred from package & stereotype conventions.')),
          btn('Customize as Policy YAML →', () => configureRules(true), 'button subtle'));
        pieces.push(styleStrip);
      }

      const actions = el('div', 'toolbar');
      if (!detailed) actions.append(btn('Open full Score & Metrics Inspector →', () => loadTab('metrics'), 'button subtle'));
      actions.append(btn('Customize policy rules', () => configureRules(true), 'button subtle'));
      if (state.capabilities?.architectureChatEnabled) {
        actions.append(btn('Ask AI to improve score', () => {
          state.chatSeedPrompt = `Review our Architecture Health v2 score (${rangeText}, band ${v2.healthBand}) and prioritize the highest-leverage structural refactorings.`;
          return loadTab('architecture-chat');
        }, 'button subtle'));
      }
      pieces.push(actions);
      container.replaceChildren(...pieces);
    };

    fetchArchitectureScoreV2(env).then(v2 => {
      if (!env.signal.aborted) renderV2(v2);
    }).catch(error => {
      if (env.signal.aborted) return;
      container.replaceChildren(
        el('p', 'small muted', error.message || 'Architecture Health v2 is unavailable for this context.'),
        btn('Retry Architecture Health v2', () => {
          state.scoreV2Cache.clear();
          return loadTab(state.tab);
        }, 'button subtle')
      );
    });
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
      const display = M.assessment(value);
      const unavailable = value?.score == null;
      const ringNum = unavailable ? null : Number(value.score);
      const withheld = unavailable && (value?.status === 'WITHHELD' || value?.confidence?.sufficient === false);
      const dims = value.dimensions ?? [];
      const breakdownRows = dims.map(dimension => {
        const dNum = dimension.score != null && Number.isFinite(Number(dimension.score)) ? Number(dimension.score) : null;
        return {
          label: dimension.id.replace(/^health[.:]/, '').replaceAll('-', ' '),
          valueText: dimension.score != null ? String(dimension.score) : 'Unavailable',
          ratio: dNum != null ? dNum / 100 : null,
          tone: dNum == null ? 'muted' : dNum < 50 ? 'danger' : dNum < 80 ? 'warn' : 'good',
          onInspect: () => inspectScoreDimension(value, dimension, id, env)
        };
      });
      detail.replaceChildren(
        renderScoreShowcase({
          mode: 'dimensions',
          rawScore: ringNum,
          displayText: display,
          subtitle: label || 'Evaluated policy dimensions',
          statusLabel: unavailable ? (withheld ? 'Withheld' : 'Unavailable') : undefined,
          statusTone: unavailable ? 'muted' : undefined,
          breakdownRows
        }),
        append(el('div', 'toolbar'),
          btn('View findings', () => { state.activePolicyReports.set(env.context, id); return loadTab('policy'); }),
          btn('Assessment evidence', () => inspect('Architecture assessment', 'ASSESSMENT', value), 'text-button'))
      );
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
    return window.EvoArchitectureChat.createView(env, {
      M, state, $, api, inspect, inspectFinding, inspectDiagnosis, inspectCapturedRepair, loadTab, openCapturedSourceModal
    });
  }
  const views = {
    'architecture-chat': architectureChatView,
    overview(env) {
      const node = el('div'), stats = el('div', 'summary-table');
      const result = state.resultJob?.result;
      const next = append(el('section', 'workbench-next'),
        append(el('div'), el('h2', '', 'Explore this snapshot'),
          el('p', '', 'Open the repository structure, inspect coverage details, review findings, or ask about a design decision.')));
      const routes = append(el('div', 'workbench-next-actions'),
        btn('Browse structure', () => loadTab('structure'), 'button subtle'),
        btn('RepoMap & Source', () => loadTab('repomap'), 'button subtle'),
        btn('Gaps & Coverage', () => loadTab('coverage'), 'button subtle'),
        btn('Review findings', () => loadTab('policy'), 'button subtle'));
      if (state.capabilities?.architectureChatEnabled) {
        routes.append(btn('Open architecture chat', () => loadTab('architecture-chat'), 'button primary'));
      }
      next.append(routes);
      const grid = el('div', 'overview-layout');
      node.append(next, grid);
      const useV2 = Boolean(state.capabilities?.architectureScoreV2Enabled);
      if (useV2) {
        grid.append(architectureScoreV2Card(env, { detailed: false }));
      }
      if (state.capabilities?.repositoryScoringEnabled) grid.append(repositoryScoreCard(env));
      const health = card(useV2 ? 'Snapshot structure & rules' : 'Architecture assessment');
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
      const healthActions = append(el('div', 'toolbar'), btn('Review score and rules', () => configureRules(false)));
      const prevGraph = state.capabilities?.evolutionEnabled && state.resultJob ? findPreviousGraphForComparison(state.resultJob) : null;
      if (prevGraph && state.resultJob?.result?.graph) {
        const setupObj = state.setups.find(s => s.id === state.resultJob.setup);
        const repoId = typeof setupObj?.repository === 'string' ? setupObj.repository : (setupObj?.repository?.value || '');
        healthActions.append(btn('Compare with Previous Snapshot →', () => {
          location.hash = M.buildCompareHash({ left: prevGraph, right: state.resultJob.result.graph, repo: repoId, autorun: true });
        }, 'button subtle'));
      }
      health.append(healthActions);
      grid.append(health);
      if (env.jobId && result && state.capabilities?.analysisCoverageEnabled) grid.append(coverageCard(env, result));
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
      let jobGapsCursor = null;
      let graphGapItems = [];
      let visibleLimit = 80;
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
        visible.slice(0, visibleLimit).forEach(item => {
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
        const shownCount = Math.min(visible.length, visibleLimit);
        const pager = el('div', 'page-footer');
        pager.append(el('span', 'small muted', `Showing ${shownCount.toLocaleString()} of ${visible.length.toLocaleString()} loaded gaps${jobGapsCursor ? ' · More on server' : ''}`));
        if (visible.length > visibleLimit) {
          pager.append(btn('Show 80 more', () => { visibleLimit += 80; renderFiltered(); }, 'button subtle'));
        }
        if (jobGapsCursor && env.jobId) {
          pager.append(btn('Load more gaps', () => safe(loadMoreJobGaps), 'button subtle'));
        }
        recordsBox.replaceChildren(el('h3', '', `Recorded Gaps (${visible.length.toLocaleString()}${jobGapsCursor ? '+' : ''})`), list, pager);
      }

      async function loadMoreJobGaps() {
        if (!env.jobId || !jobGapsCursor) return;
        const page = await api('/analysis/jobs/' + env.jobId + '/gaps', { size: 50, cursor: jobGapsCursor }, env.signal, 'POST');
        if (env.signal.aborted) return;
        if (Array.isArray(page?.items)) cachedJobGaps.push(...page.items);
        jobGapsCursor = page?.continuation || null;
        visibleLimit = Math.max(visibleLimit, cachedJobGaps.length);
        renderFiltered();
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
            jobGapsCursor = jobRes.continuation || null;
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
      const searchTabBtn = btn('Code Search (FTS5)', () => setMode('search'), 'chip');
      modeBar.append(mapTabBtn, filesTabBtn, searchTabBtn);

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
      const searchPane = el('div');
      searchPane.hidden = true;
      layout.append(leftPane, rightPane);
      node.append(modeBar, mapToolbar, filesToolbar, metaBar, layout, searchPane);

      let activeMode = 'map', activePath = '', mapVersion = 0, fileVersion = 0, sourceVersion = 0, searchMounted = false;

      function setMode(next) {
        activeMode = next;
        mapTabBtn.classList.toggle('active', next === 'map');
        filesTabBtn.classList.toggle('active', next === 'files');
        searchTabBtn.classList.toggle('active', next === 'search');
        mapToolbar.hidden = next !== 'map';
        filesToolbar.hidden = next !== 'files';
        layout.hidden = next === 'search';
        metaBar.hidden = next === 'search';
        searchPane.hidden = next !== 'search';
        if (next === 'map') safe(loadMap);
        else if (next === 'files') safe(() => loadFiles(0));
        else if (!searchMounted && window.EvoCodeSearch?.mountCodeSearch) {
          searchMounted = true;
          window.EvoCodeSearch.mountCodeSearch(searchPane, {
            graph: env.graph, snapshot: env.snapshot, context: defaultDependencyContext(env), api, signal: env.signal,
            openSource: (p, s, e, h) => openCapturedSourceModal(env.graph, p, s, e, h),
            askAi: prompt => { state.chatSeedPrompt = prompt; loadTab('architecture-chat'); }
          });
        }
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
            const rrfBadges = window.EvoCodeSearch?.renderRrfBadges?.(f);
            cardBtn.append(head, bar, meta);
            if (rrfBadges) cardBtn.append(rrfBadges);
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
      const node = card('Rules, findings & repair studio', 'Inspect evaluated rules, diagnose findings with cited code, verify candidate patches in an isolated sandbox, and apply only after explicit confirmation.');
      if (M.isPreliminaryTier?.(state.truthTier)) {
        node.append(empty('Policy evaluation & automated repair pending',
          'Preliminary structure is currently loaded. Policy checks, diagnosis, and repair actions unlock automatically when deep semantic analysis completes.'));
        return node;
      }
      const conn = state.connections.find(c => c.setup === env.setup);
      const setupRec = state.setups.find(s => s.id === env.setup);
      const isGh = Boolean(window.EvoGitHubHub?.isGitHubConnection?.(conn, setupRec));
      if (!env.reportIds.length) {
        node.append(empty('No policy report for this context', 'Add architecture policy YAML (or auto-detect rules) to evaluate findings, or use the Ad-Hoc Repair Studio below to propose & verify refactorings.'));
        const noReportActions = append(el('div', 'toolbar'), btn('Configure rules & scoring', configureRules, 'button primary'));
        node.append(noReportActions);
        if (window.EvoRepairStudio?.mount) {
          const adhocHost = el('div');
          adhocHost.style.marginTop = '14px';
          node.append(adhocHost);
          window.EvoRepairStudio.mount(adhocHost, {
            env, reportId: null, findings: [], api, resolveLabels,
            openCapturedSource: openCapturedSourceModal, inspectFinding, askAboutFinding,
            downloadBlob, capabilities: state.capabilities, setupId: env.setup, isGitHubRepo: isGh
          });
        }
        return node;
      }
      const toolbar = el('div', 'toolbar'), reports = el('select'), kinds = el('select'); reports.setAttribute('aria-label', 'Policy report'); kinds.setAttribute('aria-label', 'Rules or findings');
      env.reportIds.forEach((report, index) => { const option = el('option', '', 'Policy report ' + (index + 1)); option.value = report.identity; reports.append(option); });
      const active = state.activePolicyReports?.get(env.context);
      if (env.reportIds.some(report => report.identity === active)) reports.value = active;
      for (const op of ['findings', 'rules']) { const option = el('option', '', op === 'rules' ? 'Evaluated rules' : 'Observed findings'); option.value = op; kinds.append(option); }
      toolbar.append(reports, kinds); node.append(toolbar); const results = el('div'); node.append(results); let version = 0;
      async function load() {
        const current = ++version, id = reports.value, op = kinds.value;
        results.replaceChildren(empty('Loading…', 'Reading the selected report.'));
        try {
          if (op === 'findings' && window.EvoRepairStudio?.mount) {
            const layout = el('div', 'rs-layout');
            const rail = el('div', 'rs-findings-rail');
            const studioHost = el('div');
            layout.append(rail, studioHost);
            const loadedFindings = [];
            let studioHandle = null;
            await pagedList(rail, env, cursor => env.report(id, op, {}, { size: 50, ...(cursor ? { cursor } : {}) }), item => {
              loadedFindings.push(item);
              const finding = item.result, rule = finding.rule?.header || finding.rule;
              const label = rule?.name || rule?.id || finding.kind || 'Evaluated rule';
              const wrap = el('div', 'rs-finding-item-wrap');
              const r = row({ label, kind: finding.classification || finding.status }, () => {
                studioHandle?.selectFinding?.(item);
                return inspectFinding(item, id, env);
              }, [rule?.severity, finding.status].filter(Boolean).join(' · '));
              r.dataset.findingId = item.identity;
              const studioBtn = btn('Studio →', () => {
                studioHandle?.selectFinding?.(item);
                studioHost.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' });
              }, 'button subtle rs-open-studio-btn');
              const issueBtn = (isGh && state.capabilities?.githubEnterpriseEnabled && window.EvoGitHubEnterprise?.openCreateIssueModal)
                ? btn('Issue', () => window.EvoGitHubEnterprise.openCreateIssueModal(env.setup, {
                    graph: env.graph, report: id, findingId: item.identity, title: label, filePath: finding.path || ''
                  }), 'button subtle rs-open-studio-btn')
                : null;
              append(wrap, r, studioBtn, issueBtn);
              return wrap;
            }, 'No observed findings', 'Review the report status and rule denominator before interpreting this result.');
            if (!env.signal.aborted && current === version) {
              studioHandle = window.EvoRepairStudio.mount(studioHost, {
                env, reportId: id, findings: loadedFindings, api, resolveLabels,
                openCapturedSource: openCapturedSourceModal, inspectFinding, askAboutFinding,
                downloadBlob, capabilities: state.capabilities, setupId: env.setup, isGitHubRepo: isGh
              });
              results.replaceChildren(layout);
            }
            return;
          }
          const target = el('div');
          await pagedList(target, env, cursor => env.report(id, op, {}, { size: 50, ...(cursor ? { cursor } : {}) }), item => {
            const finding = item.result, rule = finding.rule?.header || finding.rule;
            const label = rule?.name || rule?.id || finding.kind || 'Evaluated rule';
            const r = row({ label, kind: finding.classification || finding.status }, () => {
              if (op === 'findings') return inspectFinding(item, id, env);
              inspect(label, 'POLICY EVIDENCE', finding);
            }, [rule?.severity, finding.status].filter(Boolean).join(' · '));
            r.dataset.findingId = item.identity;
            return r;
          }, op === 'findings' ? 'No observed findings' : 'No evaluated rules', 'Review the report status and rule denominator before interpreting this result.');
          if (!env.signal.aborted && current === version) results.replaceChildren(target);
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
        ? selectControl('Spring context', springContexts.map(c => [c.identity, state.contextLabels?.get(c.identity) || formatContext(c).label]), activeCtx)
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
      if (state.capabilities?.architectureScoreV2Enabled) {
        const v2Hero = architectureScoreV2Card(env, { detailed: true });
        v2Hero.style.marginBottom = '20px';
        node.append(v2Hero);
      }
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
      if (window.EvoGraphCanvas?.renderView) {
        return window.EvoGraphCanvas.renderView(env, {
          M, state, $, api, safe, btn, card, empty, selectControl, field,
          relationshipKinds, defaultDependencyContext, labelContext, labelContextOptions,
          inspect, inspectEntity, loadTab, download, downloadBlob, setRefreshGraphTheme
        });
      }
      return empty('Dependency map unavailable', 'The graph canvas module could not be loaded.');
    },
    async dsm(env) {
      if (window.EvoDsmMatrix?.renderView) {
        return window.EvoDsmMatrix.renderView(env, {
          M, state, api, safe, btn, card, empty, defaultDependencyContext, download
        });
      }
      return empty('DSM Matrix unavailable', 'The DSM matrix module could not be loaded.');
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
    advisory(env) {
      if (window.EvoCommunityAdvisory?.renderAdvisoryView) {
        return window.EvoCommunityAdvisory.renderAdvisoryView(env, api);
      }
      return empty('Advisory links unavailable', 'The advisory overlay module could not be loaded.');
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
          exports.append(
            btn('Download report', () => {
              downloadBlob(new Blob([data.markdownReport], { type: 'text/markdown;charset=utf-8' }), 'architecture-insights.md');
            }),
            btn('Architecture Wiki / Dossier', () => {
              window.EvoCommunityAdvisory?.openWikiDossierModal?.(api, env.graph, context);
            }, 'button primary')
          );
          const summary = data.summary;
          const stats = el('div', 'stats');
          const statRows = [
            ['Entities', summary.analyzedEntities, null], ['Dependencies', summary.internalDependencies, null],
            ['Cyclic groups', summary.tangleCount, 'Strongly connected dependency loops that couple components bidirectionally.'],
            ['Max. transitive dependents', summary.maxBlastRadius, 'Largest blast radius reachable from any single component in this scope.']
          ];
          if (summary.abstractness != null) statRows.push(['Abstractness (A)', Number(summary.abstractness).toFixed(2), M.explainMetric?.('A')?.plain]);
          if (summary.distanceFromMainSequence != null) statRows.push(['Main-Sequence Dist. (D)', Number(summary.distanceFromMainSequence).toFixed(2), M.explainMetric?.('D')?.plain]);
          if (summary.edgeSdpViolationCount != null) statRows.push(['SDP Violations', summary.edgeSdpViolationCount, M.explainMetric?.('SDP')?.plain]);
          if (Array.isArray(data.communities)) statRows.push(['Leiden Communities', `${data.communities.length} (Q=${Number(data.communityModularity ?? 0).toFixed(2)})`, 'Deterministic Traag et al. (2019) Leiden structural communities.']);
          if (Array.isArray(data.misplacedTypes)) statRows.push(['Misplaced Types (Drift)', data.misplacedTypes.length, 'Types whose structural dependencies pull them outside their declared package.']);
          for (const [label, value, help] of statRows) {
            const tile = append(el('div', 'stat'), el('span', '', label), el('strong', '', value));
            if (help) tile.title = help;
            stats.append(tile);
          }
          body.append(stats);

          window.EvoCommunityAdvisory?.renderCommunitiesInsights?.(body, data, {
            scope: sc,
            openCommunityInMap: (seed, targetScope) => {
              state.graphFocus = { seed: seed || null, scope: targetScope, groupBy: 'COMMUNITY', context, relationshipKinds: relationshipKinds(targetScope) };
              return loadTab('graph');
            }
          });

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
            const conn = state.connections.find(c => c.setup === env.setup);
            const setupRec = state.setups.find(s => s.id === env.setup);
            const isGh = Boolean(window.EvoGitHubHub?.isGitHubConnection?.(conn, setupRec));
            const canIssue = Boolean(isGh && state.capabilities?.githubEnterpriseEnabled && window.EvoGitHubEnterprise?.openCreateIssueModal);
            hotspots.append(table(
              canIssue ? ['Entity', 'Archetype', 'Risk score', 'Dependents', 'Direct in / out', 'Action'] : ['Entity', 'Archetype', 'Risk score', 'Dependents', 'Direct in / out'],
              data.hotspots.map(h => {
                const fill = el('div', 'hotspot-risk-fill');
                fill.style.width = Math.min(100, Math.round(((h.hotspotScore || 0) / maxRisk) * 100)) + '%';
                const riskCell = append(el('div', 'hotspot-risk-wrap'), el('span', '', h.hotspotScore), append(el('div', 'hotspot-risk-bar'), fill));
                const owners = Array.isArray(h.owners) && h.owners.length ? h.owners : (h.path ? window.EvoGitHubEnterprise?.ownersForPath?.(env.graph, h.path) : []);
                const entityCell = append(el('td'), append(el('div', 'gh-owner-row'),
                  btn(h.label, () => openGraph(h.identity, sc), 'text-button'),
                  window.EvoGitHubEnterprise?.makeOwnerPills?.(owners)
                ));
                const cols = [
                  entityCell,
                  el('td', '', h.archetype || '—'),
                  append(el('td'), riskCell),
                  el('td', '', h.transitiveDependents),
                  el('td', '', h.fanIn + ' / ' + h.fanOut)
                ];
                if (canIssue) {
                  cols.push(append(el('td'), btn('Create Issue', () => window.EvoGitHubEnterprise.openCreateIssueModal(env.setup, {
                    graph: env.graph,
                    hotspotSymbol: h.label || h.identity,
                    title: `Refactor high-risk hotspot ${h.label}`,
                    filePath: h.path || ''
                  }), 'button subtle')));
                }
                return append(el('tr'), ...cols);
              })
            ));
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
  document.querySelectorAll('[data-action=connect]').forEach(n => n.addEventListener('click', () => {
    $('connect-title').textContent = 'Connect your code';
    $('connect-error').hidden = true;
    window.EvoGitHubHub?.setConnectTab?.('manual');
    $('connect-dialog').showModal();
  }));
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
  $('run-details').addEventListener('toggle', () => { if ($('run-details').open) requestAnimationFrame(syncStepTrackGeometry); });
  $('job-events').addEventListener('toggle', () => requestAnimationFrame(syncStepTrackGeometry), true);
  if (typeof ResizeObserver === 'function') new ResizeObserver(() => syncStepTrackGeometry()).observe($('job-events'));
  $('context').addEventListener('focus', labelContextOptions);
  $('context').addEventListener('pointerenter', labelContextOptions);
  $('context-picker-shell')?.addEventListener('focusin', labelContextOptions);
  $('context-picker-shell')?.addEventListener('pointerenter', labelContextOptions);
  $('analyze').addEventListener('click', () => safe(() => submit()));
  $('analyze-force').addEventListener('click', () => safe(() => submit(true)));
  $('cancel-job').addEventListener('click', () => safe(async () => {
    const id = state.job?.id, generation = state.pollGeneration; if (!id) return;
    const job = await api('/analysis/jobs/' + id + '/cancel', {});
    if (selectedJob(id, generation) && job.revision >= state.job.revision) { state.job = job; renderJob(job); }
  }));
  $('history-more').addEventListener('click', () => safe(() => refreshHistory(true)));
  $('context').addEventListener('change', () => { state.graphFocus = null; state.explorer = null; state.metricSubject = null; state.context = $('context').value; notifyContextsUpdated(); safe(() => loadTab(state.tab)); });
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
    if (event.key === '/' && !inInput && !event.ctrlKey && !event.metaKey) {
      if (!$('analysis-workspace').hidden && !$('connect-dialog').open && !$('inspector').open && !$('github-session-dialog')?.open && !$('github-auth-dialog')?.open && !$('workspace-account-dialog')?.open) {
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
    const sun = '<svg class="u219-sun" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/></svg>';
    const moon = '<svg class="u219-moon" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 15.5A8 8 0 0 1 8.5 4 8 8 0 1 0 20 15.5Z"/></svg>';
    const toggle = $('theme-toggle');
    toggle.innerHTML = sun + moon;
    toggle.className = 'icon-button u219-tg' + (theme === 'light' ? ' is-light' : '');
    toggle.setAttribute('aria-label', label);
    toggle.setAttribute('aria-pressed', String(theme === 'light'));
    toggle.setAttribute('data-tooltip', '');
    try { localStorage.setItem('evo_theme', theme); } catch (_) { }
  }
  let theme = 'dark'; try { theme = localStorage.getItem('evo_theme') === 'light' ? 'light' : 'dark'; } catch (_) { } applyTheme(theme);
  $('theme-toggle').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    window.EvoThemeReveal?.trigger?.($('theme-toggle'), next, () => {
      applyTheme(next);
    }) || applyTheme(next);
  });
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
  $('open-wiki-dossier')?.addEventListener('click', () => {
    $('workspace-tools').open = false;
    void window.EvoCommunityAdvisory?.openWikiDossierModal?.(api, state.graph, state.context);
  });
  $('open-mcp-setup')?.addEventListener('click', () => {
    $('workspace-tools').open = false;
    void window.EvoCommunityAdvisory?.openMcpSetupModal?.(api);
  });
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
    },
    openWikiDossier() {
      return window.EvoCommunityAdvisory?.openWikiDossierModal?.(api, state.graph, state.context);
    },
    openMcpSetup() {
      return window.EvoCommunityAdvisory?.openMcpSetupModal?.(api);
    }
  };
  window.EvoApp = { state, api, inspect, loadTab, selectRepo, selectJob, configureRules, inspectFinding, inspectDiagnosis, inspectRepairProposal, inspectRepairVerification, downloadBlob, connectAndAnalyze, submit };
  if (window.EvoBus) {
    window.EvoBus.on('evo:canvas-focus', payload => {
      if (!state.graph || !payload) return;
      const ids = Array.isArray(payload.nodeIds) ? payload.nodeIds.filter(Boolean) : [];
      if (!ids.length) return;
      if (state.tab !== 'graph') {
        state.graphFocus = { seed: ids[0], focusNodes: ids, level: 'MICRO', context: payload.contextId || state.context, openInspector: Boolean(payload.openInspector) };
        void safe(() => loadTab('graph'));
      }
    });
    window.EvoBus.on('evo:chat-seed', payload => {
      if (!state.graph || !payload) return;
      if (state.tab !== 'architecture-chat') {
        state.chatSeedPrompt = payload.prompt || '';
        state.chatSeedMode = payload.mode || '';
        state.chatSeedFocus = payload.focusNodeId || '';
        void safe(() => loadTab('architecture-chat'));
      }
    });
    window.EvoBus.on('evo:source-open', payload => {
      if (!state.graph || !payload?.path) return;
      void safe(() => openCapturedSourceModal(state.graph, payload.path, payload.startLine || 1, payload.endLine || null, payload.highlightLine || payload.startLine || null));
    });
    window.EvoBus.on('evo:repair-seed', payload => {
      if (!state.graph || !payload) return;
      state.pendingRepairSeed = payload;
      if (state.tab !== 'policy') void safe(() => loadTab('policy'));
    });
  }
  window.EvoGitHubAuth?.onChange?.(info => {
    window.EvoBus?.emit?.('evo:auth-changed', info);
    if (info?.changedUser) void safe(handleTenantSwitch);
    else void window.EvoGitHubHub?.refreshAuthStatus?.();
  });
  $('disconnect-dialog').addEventListener('close', () => {
    const options = $('run-options');
    if (options && !options.hidden && !$('run-controls').hidden) options.querySelector('summary')?.focus();
    else document.querySelector('[data-action=connect]')?.focus() || $('repository-title')?.focus();
  });
  window.addEventListener('pagehide', closeJobStream);
  window.addEventListener('hashchange', updateWorkspaceTool);
  safe(connectServer);
})();
