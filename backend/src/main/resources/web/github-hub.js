/* GitHub Hub, Smart Intake, RAM-Only Session Auth, PR Comparison & Pipeline Stepper.
 * Strict zero-XSS DOM construction; never persists GitHub tokens to disk or browser storage. */
(() => {
  'use strict';
  const M = window.EvoModel;
  const $ = id => document.getElementById(id);
  const el = (tag, cls, text) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = String(text);
    return n;
  };
  const append = (parent, ...children) => {
    children.filter(Boolean).forEach(child => parent.append(child));
    return parent;
  };
  const btn = (text, action, cls = 'button') => {
    const n = el('button', cls, text);
    n.type = 'button';
    n.addEventListener('click', action);
    return n;
  };

  const hubState = {
    authStatus: null,
    selectedRefBySetup: new Map(),
    selectedPrBySetup: new Map(),
    repoMetaBySetup: new Map(),
    connectParsed: null,
    connectSelectedRef: '',
    connectComparePrOnConnect: false,
    connectAutoPolicy: true,
    refsController: null,
    repoHubController: null,
    repoBrowserController: null,
    activeComparison: null,
    browser: {
      mode: 'manual', // 'manual' | 'github'
      org: '',
      q: '',
      visibility: 'all',
      sort: 'updated',
      page: 1,
      hasMore: false,
      orgs: [],
      repos: [],
      loadedOrgs: false
    }
  };

  function getApi() {
    return window.EvoApp?.api;
  }
  function getAppState() {
    return window.EvoApp?.state;
  }
  function isGitHubEnabled() {
    return Boolean(getAppState()?.capabilities?.githubIntegrationEnabled);
  }
  function getConnectionForSetup(setupId) {
    const appState = getAppState();
    if (!appState || !setupId) return null;
    return (appState.connections || []).find(c => c.setup === setupId) || null;
  }
  function getSetupRecord(setupId) {
    const appState = getAppState();
    if (!appState || !setupId) return null;
    return (appState.setups || []).find(s => s.id === setupId) || null;
  }
  function isGitHubConnection(conn, setup) {
    if (!conn && !setup) return false;
    if (conn?.github || conn?.provider === 'GITHUB') return true;
    const coord = String(
      conn?.repository?.canonicalCoordinate ||
      conn?.source ||
      conn?.url ||
      setup?.repository?.canonicalCoordinate ||
      setup?.repository ||
      ''
    );
    return /github\.com/i.test(coord) || conn?.kind === 'GIT_HTTPS' && /github/i.test(String(conn?.label || ''));
  }

  /* ── 1. GitHub Auth Status & RAM-Only Session PAT Modal ─────────────────── */
  async function refreshAuthStatus() {
    const api = getApi();
    if (!api || !isGitHubEnabled()) {
      hubState.authStatus = null;
      renderAuthPills();
      return null;
    }
    try {
      const status = await api('/github/auth/status', undefined, undefined, 'GET', 10000);
      hubState.authStatus = status || null;
    } catch (_) {
      hubState.authStatus = { authenticated: false, source: 'NONE', unavailable: true };
    }
    renderAuthPills();
    return hubState.authStatus;
  }

  function formatAuthLabel(status) {
    if (!status) return { text: 'GitHub auth unavailable', tone: 'muted', detail: '' };
    if (status.authenticated) {
      const login = status.login || status.username || 'authenticated';
      const srcMap = {
        OAUTH_WEB: 'OAuth Web',
        OAUTH_DEVICE: 'Device Code',
        SESSION_PAT: 'Session PAT (RAM only)',
        GH_CLI: 'gh CLI',
        ENV_TOKEN: 'Env token',
        GITHUB_TOKEN: 'Env token'
      };
      const srcLabel = srcMap[String(status.source || '').toUpperCase()] || status.source || 'connected';
      const rate = status.rateLimitRemaining != null && status.rateLimitLimit != null
        ? ` · ${Number(status.rateLimitRemaining).toLocaleString()}/${Number(status.rateLimitLimit).toLocaleString()} API req`
        : '';
      return { text: `GitHub: @${login}`, tone: 'good', detail: `${srcLabel}${rate}` };
    }
    const rate = status.rateLimitRemaining != null
      ? ` (${Number(status.rateLimitRemaining)} req/hr public)`
      : ' (public repos only)';
    return { text: 'GitHub: Unauthenticated', tone: 'warn', detail: rate };
  }

  function renderAuthPills() {
    const targets = [
      $('connect-github-auth'),
      $('github-hub-auth-slot')
    ].filter(Boolean);
    const enabled = isGitHubEnabled();
    for (const host of targets) {
      if (!enabled) {
        host.hidden = true;
        host.replaceChildren();
        continue;
      }
      host.hidden = false;
      const info = formatAuthLabel(hubState.authStatus);
      const dot = el('span', 'gh-auth-dot is-' + info.tone);
      dot.setAttribute('aria-hidden', 'true');
      const label = el('span', 'gh-auth-label', info.text);
      const meta = info.detail ? el('span', 'gh-auth-meta', info.detail) : null;
      const manageBtn = btn(
        hubState.authStatus?.authenticated ? 'Session token…' : 'Add session PAT…',
        () => openSessionPatDialog(),
        'text-button gh-auth-action'
      );
      const multiSignIn = (!hubState.authStatus?.authenticated && window.EvoGitHubAuth?.openSignInModal && (getAppState()?.capabilities?.multiUserAuthEnabled || getAppState()?.capabilities?.oauthWebEnabled || getAppState()?.capabilities?.deviceFlowEnabled))
        ? btn('Sign in (OAuth / Device)…', () => window.EvoGitHubAuth.openSignInModal(), 'text-button')
        : null;
      host.replaceChildren(...[dot, label, meta, multiSignIn, manageBtn].filter(Boolean));
    }
  }

  function openSessionPatDialog() {
    const dialog = $('github-session-dialog');
    if (!dialog) return;
    const input = $('github-session-pat-input');
    const err = $('github-session-pat-error');
    const statusNote = $('github-session-pat-status');
    const clearBtn = $('github-session-pat-clear');
    if (input) input.value = '';
    if (err) { err.hidden = true; err.textContent = ''; }
    const s = hubState.authStatus;
    if (statusNote) {
      statusNote.textContent = s?.authenticated
        ? `Currently signed in as @${s.login || s.username || 'user'} via ${s.source || 'token'}.`
        : 'No active session token. Public GitHub repositories use unauthenticated limits (60 req/hr).';
    }
    if (clearBtn) {
      clearBtn.hidden = !(s?.authenticated && String(s.source || '').toUpperCase() === 'SESSION_PAT');
    }
    if (!dialog.open) dialog.showModal();
    input?.focus();
  }

  function wireSessionPatDialog() {
    const dialog = $('github-session-dialog');
    const form = $('github-session-pat-form');
    const input = $('github-session-pat-input');
    const err = $('github-session-pat-error');
    const saveBtn = $('github-session-pat-save');
    const clearBtn = $('github-session-pat-clear');
    if (!dialog || !form || !input) return;

    dialog.addEventListener('close', () => {
      input.value = '';
    });

    form.addEventListener('submit', async event => {
      event.preventDefault();
      const api = getApi();
      const token = input.value.trim();
      if (!token) {
        err.textContent = 'Paste a GitHub personal access token first.';
        err.hidden = false;
        return;
      }
      err.hidden = true;
      saveBtn.disabled = true;
      try {
        const res = await api('/github/auth/session', { token }, undefined, 'POST', 15000);
        input.value = '';
        hubState.authStatus = res || await refreshAuthStatus();
        renderAuthPills();
        dialog.close();
        window.EvoToast?.push?.({
          kind: 'ok',
          title: 'GitHub session token active (RAM only)',
          detail: hubState.authStatus?.login ? '@' + hubState.authStatus.login : 'Never persisted to disk'
        });
        if ($('connect-dialog')?.open && hubState.connectParsed?.repoUrl) {
          void lookupConnectRefs(hubState.connectParsed);
        }
        if (getAppState()?.setup) {
          void syncConnectedRepoBar(getAppState().setup);
        }
      } catch (e) {
        err.textContent = e.message || 'Could not validate GitHub token.';
        err.hidden = false;
      } finally {
        saveBtn.disabled = false;
      }
    });

    clearBtn?.addEventListener('click', async () => {
      const api = getApi();
      clearBtn.disabled = true;
      err.hidden = true;
      try {
        await api('/github/auth/session', undefined, undefined, 'DELETE', 10000);
        input.value = '';
        await refreshAuthStatus();
        dialog.close();
        window.EvoToast?.push?.({ kind: 'info', title: 'GitHub session token cleared from RAM' });
      } catch (e) {
        err.textContent = e.message || 'Could not clear session token.';
        err.hidden = false;
      } finally {
        clearBtn.disabled = false;
      }
    });
  }

  /* ── 2. Smart Connect Dialog Intake (URL/PR auto-detect + live refs) ────── */
  async function lookupConnectRefs(parsed) {
    const refBox = $('connect-ref-box');
    const refSelect = $('connect-ref-select');
    const refStatus = $('connect-ref-status');
    const prCompareWrap = $('connect-pr-compare-wrap');
    const prCompareInput = $('connect-pr-compare');
    if (!refBox || !refSelect) return;
    if (!parsed || !['GITHUB_REPO', 'GITHUB_PR'].includes(parsed.kind) || !isGitHubEnabled()) {
      refBox.hidden = true;
      if (prCompareWrap) prCompareWrap.hidden = true;
      return;
    }

    hubState.refsController?.abort();
    const controller = new AbortController();
    hubState.refsController = controller;
    refBox.hidden = false;
    refSelect.disabled = true;
    refSelect.replaceChildren(new Option('Loading branches, tags & PRs…', ''));
    if (refStatus) refStatus.textContent = `Fetching refs for ${parsed.owner}/${parsed.repo}…`;

    try {
      const api = getApi();
      const repoParam = encodeURIComponent(`${parsed.owner}/${parsed.repo}`);
      const refs = await api(`/github/refs?repo=${repoParam}`, undefined, controller.signal, 'GET', 15000);
      if (controller.signal.aborted) return;

      refSelect.replaceChildren();
      const defaultBranch = refs?.defaultBranch || 'main';
      refSelect.append(new Option(`Default branch (${defaultBranch})`, ''));

      const branches = Array.isArray(refs?.branches) ? refs.branches : [];
      if (branches.length) {
        const grp = el('optgroup');
        grp.label = `Branches (${branches.length})`;
        for (const b of branches.slice(0, 60)) {
          const name = typeof b === 'string' ? b : (b.name || b.ref);
          if (!name) continue;
          grp.append(new Option(name + (name === defaultBranch ? ' (default)' : ''), name));
        }
        refSelect.append(grp);
      }

      const pulls = Array.isArray(refs?.pulls) ? refs.pulls : [];
      if (pulls.length) {
        const grp = el('optgroup');
        grp.label = `Open Pull Requests (${pulls.length})`;
        for (const pr of pulls.slice(0, 40)) {
          const num = pr.number;
          const headRef = pr.headRef || pr.head?.ref || `pull/${num}/head`;
          const baseRef = pr.baseRef || pr.base?.ref || defaultBranch;
          const label = `PR #${num} · ${(pr.title || 'Pull Request').slice(0, 56)} (${headRef} → ${baseRef})`;
          const opt = new Option(label, pr.ref || `pull/${num}/head`);
          opt.dataset.pullNumber = String(num);
          opt.dataset.baseRef = baseRef;
          opt.dataset.headRef = headRef;
          grp.append(opt);
        }
        refSelect.append(grp);
      }

      const tags = Array.isArray(refs?.tags) ? refs.tags : [];
      if (tags.length) {
        const grp = el('optgroup');
        grp.label = `Tags (${tags.length})`;
        for (const t of tags.slice(0, 40)) {
          const name = typeof t === 'string' ? t : (t.name || t.ref);
          if (!name) continue;
          grp.append(new Option(name, name));
        }
        refSelect.append(grp);
      }

      refSelect.disabled = false;
      if (parsed.pullNumber) {
        const prOpt = [...refSelect.options].find(o => Number(o.dataset.pullNumber) === parsed.pullNumber);
        if (prOpt) {
          refSelect.value = prOpt.value;
        } else {
          const custom = new Option(`PR #${parsed.pullNumber} (head)`, `pull/${parsed.pullNumber}/head`);
          custom.dataset.pullNumber = String(parsed.pullNumber);
          refSelect.append(custom);
          refSelect.value = custom.value;
        }
        hubState.connectSelectedRef = refSelect.value;
        if (prCompareWrap) prCompareWrap.hidden = false;
        if (prCompareInput) {
          prCompareInput.checked = true;
          hubState.connectComparePrOnConnect = true;
        }
      } else if (parsed.ref) {
        if (![...refSelect.options].some(o => o.value === parsed.ref)) {
          refSelect.append(new Option(parsed.ref, parsed.ref));
        }
        refSelect.value = parsed.ref;
        hubState.connectSelectedRef = parsed.ref;
        if (prCompareWrap) prCompareWrap.hidden = true;
      } else {
        hubState.connectSelectedRef = '';
        if (prCompareWrap) prCompareWrap.hidden = true;
      }

      if (refStatus) {
        const parts = [];
        if (branches.length) parts.push(`${branches.length} branches`);
        if (pulls.length) parts.push(`${pulls.length} open PRs`);
        if (tags.length) parts.push(`${tags.length} tags`);
        refStatus.textContent = parts.length
          ? `Detected ${parsed.owner}/${parsed.repo} · ${parts.join(' · ')}`
          : `Detected ${parsed.owner}/${parsed.repo}`;
      }
    } catch (e) {
      if (controller.signal.aborted) return;
      refSelect.disabled = false;
      refSelect.replaceChildren(new Option('Default branch', ''));
      if (parsed.ref) {
        refSelect.append(new Option(parsed.ref, parsed.ref));
        refSelect.value = parsed.ref;
        hubState.connectSelectedRef = parsed.ref;
      }
      if (refStatus) {
        refStatus.textContent = e.code === 'GITHUB_AUTH_REQUIRED'
          ? 'Private repository or auth needed — add a RAM-only session PAT above to browse branches & PRs.'
          : (e.message || 'Could not list remote refs; connecting will use the default branch.');
      }
    }
  }

  function wireConnectSmartIntake() {
    const sourceInput = $('repository-source');
    const preview = $('connect-smart-preview');
    const refSelect = $('connect-ref-select');
    const prCompareWrap = $('connect-pr-compare-wrap');
    const prCompareInput = $('connect-pr-compare');
    const autoPolicyCheck = $('connect-auto-policy');
    if (!sourceInput) return;

    let timer = null;
    const syncParsed = () => {
      const parsed = M.parseRepositoryInput ? M.parseRepositoryInput(sourceInput.value) : { kind: 'EMPTY' };
      hubState.connectParsed = parsed;
      if (!preview) return;
      if (parsed.kind === 'EMPTY') {
        preview.hidden = true;
        preview.replaceChildren();
        const refBox = $('connect-ref-box');
        if (refBox) refBox.hidden = true;
        if (prCompareWrap) prCompareWrap.hidden = true;
        hubState.connectSelectedRef = '';
        return;
      }
      const kindMap = {
        LOCAL_PATH: { label: 'Local folder', cls: 'is-local', tip: 'Captured read-only; source is never executed on the host.' },
        GITHUB_REPO: { label: 'GitHub repo', cls: 'is-github', tip: parsed.ref ? `Branch/ref: ${parsed.ref}` : 'Public or session-authenticated HTTPS clone.' },
        GITHUB_PR: { label: `GitHub PR #${parsed.pullNumber}`, cls: 'is-pr', tip: 'Pull request detected — analyze head or compare base vs head.' },
        GIT_HTTPS: { label: 'HTTPS Git', cls: 'is-git', tip: 'Cloned read-only into an isolated capture snapshot.' }
      };
      const meta = kindMap[parsed.kind] || kindMap.LOCAL_PATH;
      preview.hidden = false;
      preview.replaceChildren(
        el('span', 'gh-intake-badge ' + meta.cls, meta.label),
        el('strong', 'gh-intake-target', parsed.displayLabel || parsed.normalizedSource),
        el('span', 'gh-intake-tip', meta.tip)
      );

      clearTimeout(timer);
      if (['GITHUB_REPO', 'GITHUB_PR'].includes(parsed.kind)) {
        timer = setTimeout(() => void lookupConnectRefs(parsed), 240);
      } else {
        hubState.refsController?.abort();
        const refBox = $('connect-ref-box');
        if (refBox) refBox.hidden = true;
        if (prCompareWrap) prCompareWrap.hidden = true;
        hubState.connectSelectedRef = '';
      }
    };

    sourceInput.addEventListener('input', syncParsed);
    refSelect?.addEventListener('change', () => {
      hubState.connectSelectedRef = refSelect.value || '';
      const selectedOpt = refSelect.selectedOptions[0];
      const isPr = Boolean(selectedOpt?.dataset?.pullNumber);
      if (prCompareWrap) prCompareWrap.hidden = !isPr;
      if (prCompareInput && !isPr) {
        prCompareInput.checked = false;
        hubState.connectComparePrOnConnect = false;
      }
    });
    prCompareInput?.addEventListener('change', () => {
      hubState.connectComparePrOnConnect = Boolean(prCompareInput.checked);
    });
    autoPolicyCheck?.addEventListener('change', () => {
      hubState.connectAutoPolicy = Boolean(autoPolicyCheck.checked);
    });

    $('connect-dialog')?.addEventListener('close', () => {
      hubState.refsController?.abort();
    });
  }

  function getConnectSubmissionOptions() {
    const rawSource = $('repository-source')?.value.trim() || '';
    const parsed = M.parseRepositoryInput ? M.parseRepositoryInput(rawSource) : { kind: 'LOCAL_PATH', normalizedSource: rawSource };
    const source = ['GITHUB_REPO', 'GITHUB_PR'].includes(parsed.kind) ? parsed.normalizedSource : rawSource;
    const refSelect = $('connect-ref-select');
    const selectedOpt = refSelect?.selectedOptions?.[0];
    const ref = (refSelect && !refSelect.disabled && refSelect.value) ? refSelect.value : (parsed.ref || '');
    const pullNumber = selectedOpt?.dataset?.pullNumber ? Number(selectedOpt.dataset.pullNumber) : parsed.pullNumber;
    const comparePrAfterConnect = Boolean(pullNumber && $('connect-pr-compare')?.checked);
    const autoDetectPolicy = $('connect-auto-policy') ? Boolean($('connect-auto-policy').checked) : true;
    return { source, ref, pullNumber, comparePrAfterConnect, autoDetectPolicy, parsed };
  }

  function setSelectedRef(setupId, ref, pullNumber = null) {
    if (!setupId) return;
    if (ref) hubState.selectedRefBySetup.set(setupId, ref);
    else hubState.selectedRefBySetup.delete(setupId);
    if (pullNumber) hubState.selectedPrBySetup.set(setupId, Number(pullNumber));
    else hubState.selectedPrBySetup.delete(setupId);
  }

  function getSelectedRef(setupId) {
    return setupId ? (hubState.selectedRefBySetup.get(setupId) || '') : '';
  }

  /* ── 3. Connected Repository GitHub Toolbar + 1-Click PR Compare ────────── */
  async function syncConnectedRepoBar(setupId) {
    const bar = $('github-hub-bar');
    if (!bar) return;
    hubState.repoHubController?.abort();

    const conn = getConnectionForSetup(setupId);
    const setup = getSetupRecord(setupId);
    if (!setupId || !isGitHubEnabled() || !isGitHubConnection(conn, setup)) {
      bar.hidden = true;
      bar.replaceChildren();
      return;
    }

    const controller = new AbortController();
    hubState.repoHubController = controller;
    bar.hidden = false;
    bar.replaceChildren(el('span', 'small muted', 'Loading GitHub branches, tags, and open pull requests…'));

    const api = getApi();
    try {
      const [meta, branchesRes, tagsRes, pullsRes] = await Promise.all([
        api(`/github/connections/${encodeURIComponent(setupId)}/metadata`, undefined, controller.signal, 'GET', 12000).catch(() => null),
        api(`/github/connections/${encodeURIComponent(setupId)}/branches`, undefined, controller.signal, 'GET', 12000).catch(() => ({ items: [] })),
        api(`/github/connections/${encodeURIComponent(setupId)}/tags`, undefined, controller.signal, 'GET', 12000).catch(() => ({ items: [] })),
        api(`/github/connections/${encodeURIComponent(setupId)}/pulls`, undefined, controller.signal, 'GET', 12000).catch(() => ({ items: [] }))
      ]);
      if (controller.signal.aborted || getAppState()?.setup !== setupId) return;

      const branches = branchesRes?.items || branchesRes?.branches || [];
      const tags = tagsRes?.items || tagsRes?.tags || [];
      const pulls = pullsRes?.items || pullsRes?.pulls || [];
      const defaultBranch = meta?.defaultBranch || 'main';
      if (meta) {
        const fullName = meta.fullName || '';
        const parts = fullName.split('/');
        hubState.repoMetaBySetup.set(setupId, {
          ...meta,
          owner: meta.owner || parts[0] || '',
          repo: meta.repo || parts[1] || '',
          defaultBranch,
          headSha: meta.headSha || meta.commitSha || '',
          host: meta.host || 'github.com'
        });
      }

      const leftGroup = el('div', 'gh-bar-left');
      const repoBadge = el('span', 'gh-repo-pill', meta?.fullName || conn?.label || setupId);
      if (meta?.private) repoBadge.append(el('span', 'badge warn', 'Private'));
      const metaSub = el('span', 'small muted', [
        `Default: ${defaultBranch}`,
        pulls.length ? `${pulls.length} open PR${pulls.length === 1 ? '' : 's'}` : '0 open PRs',
        meta?.primaryLanguage || null
      ].filter(Boolean).join(' · '));
      leftGroup.append(repoBadge, metaSub);

      const controls = el('div', 'gh-bar-controls');
      const refSel = el('select', 'gh-ref-select');
      refSel.id = 'github-active-ref';
      refSel.setAttribute('aria-label', 'GitHub branch, tag or pull request');
      refSel.append(new Option(`Default (${defaultBranch})`, ''));

      if (branches.length) {
        const grp = el('optgroup');
        grp.label = 'Branches';
        for (const b of branches.slice(0, 60)) {
          const name = typeof b === 'string' ? b : (b.name || b.ref);
          if (name) grp.append(new Option(name, name));
        }
        refSel.append(grp);
      }
      if (pulls.length) {
        const grp = el('optgroup');
        grp.label = 'Open Pull Requests';
        for (const pr of pulls.slice(0, 40)) {
          const head = pr.headRef || pr.head?.ref || `pull/${pr.number}/head`;
          const base = pr.baseRef || pr.base?.ref || defaultBranch;
          const opt = new Option(`PR #${pr.number} · ${(pr.title || '').slice(0, 48)} (${head} → ${base})`, pr.ref || `pull/${pr.number}/head`);
          opt.dataset.pullNumber = String(pr.number);
          opt.dataset.baseRef = base;
          opt.dataset.headRef = head;
          opt.dataset.prTitle = pr.title || `PR #${pr.number}`;
          grp.append(opt);
        }
        refSel.append(grp);
      }
      if (tags.length) {
        const grp = el('optgroup');
        grp.label = 'Tags';
        for (const t of tags.slice(0, 40)) {
          const name = typeof t === 'string' ? t : (t.name || t.ref);
          if (name) grp.append(new Option(name, name));
        }
        refSel.append(grp);
      }

      const savedRef = getSelectedRef(setupId);
      if (savedRef) {
        if (![...refSel.options].some(o => o.value === savedRef)) {
          refSel.append(new Option(savedRef, savedRef));
        }
        refSel.value = savedRef;
      }

      const comparePrBtn = btn('Compare PR (Base vs Head)', () => {
        const opt = refSel.selectedOptions[0];
        const prNum = opt?.dataset?.pullNumber ? Number(opt.dataset.pullNumber) : (pulls[0]?.number || null);
        if (prNum) {
          void openPrComparisonModal(setupId, {
            pullNumber: prNum,
            baseRef: opt?.dataset?.baseRef || pulls[0]?.baseRef || defaultBranch,
            headRef: opt?.dataset?.headRef || pulls[0]?.headRef || `pull/${prNum}/head`,
            title: opt?.dataset?.prTitle || pulls[0]?.title || `PR #${prNum}`
          });
        } else {
          const targetRef = refSel.value || defaultBranch;
          void openPrComparisonModal(setupId, { baseRef: defaultBranch, headRef: targetRef, title: `${defaultBranch} vs ${targetRef}` });
        }
      }, 'button subtle gh-compare-pr-btn');
      comparePrBtn.id = 'github-compare-pr-btn';

      const updateCompareBtnLabel = () => {
        const opt = refSel.selectedOptions[0];
        if (opt?.dataset?.pullNumber) {
          comparePrBtn.textContent = `Compare PR #${opt.dataset.pullNumber} (Base vs Head)`;
          comparePrBtn.hidden = false;
        } else if (pulls.length) {
          comparePrBtn.textContent = `Compare PR #${pulls[0].number} (Base vs Head)`;
          comparePrBtn.hidden = false;
        } else if (refSel.value && refSel.value !== defaultBranch) {
          comparePrBtn.textContent = `Compare ${defaultBranch} vs ${refSel.value}`;
          comparePrBtn.hidden = false;
        } else {
          comparePrBtn.hidden = true;
        }
      };
      updateCompareBtnLabel();

      refSel.addEventListener('change', () => {
        const opt = refSel.selectedOptions[0];
        setSelectedRef(setupId, refSel.value, opt?.dataset?.pullNumber ? Number(opt.dataset.pullNumber) : null);
        updateCompareBtnLabel();
      });

      const authSlot = el('div', 'gh-auth-pill');
      authSlot.id = 'github-hub-auth-slot';

      const caps = getAppState()?.capabilities || {};
      let entCluster = null;
      if ((caps.githubEnterpriseEnabled || caps.sarifExportEnabled || caps.webhooksEnabled) && window.EvoGitHubEnterprise?.openEnterpriseHub) {
        entCluster = el('div', 'gh-ent-bar-btns');
        entCluster.append(
          btn('PRs & Quality Gate', () => window.EvoGitHubEnterprise.openEnterpriseHub(setupId, 'pulls'), 'button subtle gh-ent-bar-btn'),
          btn('SARIF', () => window.EvoGitHubEnterprise.openEnterpriseHub(setupId, 'sarif'), 'button subtle gh-ent-bar-btn'),
          btn('CI YAML', () => window.EvoGitHubEnterprise.openEnterpriseHub(setupId, 'ci'), 'button subtle gh-ent-bar-btn'),
          btn('Webhooks', () => window.EvoGitHubEnterprise.openEnterpriseHub(setupId, 'webhooks'), 'button subtle gh-ent-bar-btn')
        );
      }

      append(controls, refSel, comparePrBtn, entCluster, authSlot);
      bar.replaceChildren(leftGroup, controls);
      renderAuthPills();
    } catch (e) {
      if (controller.signal.aborted) return;
      const authSlot = el('div', 'gh-auth-pill');
      authSlot.id = 'github-hub-auth-slot';
      bar.replaceChildren(
        el('span', 'small muted', e.message || 'GitHub metadata unavailable.'),
        authSlot
      );
      renderAuthPills();
    }
  }

  async function openPrComparisonModal(setupId, target) {
    const api = getApi();
    const inspect = window.EvoApp?.inspect;
    if (!api || !inspect) return;

    const title = target.pullNumber
      ? `PR #${target.pullNumber} Architecture Comparison`
      : `Branch Comparison · ${target.baseRef} → ${target.headRef}`;

    return inspect(title, 'GITHUB PR COMPARISON', async signal => {
      const reqBody = target.pullNumber
        ? { pullNumber: target.pullNumber }
        : { baseRef: target.baseRef, headRef: target.headRef };
      const initial = await api(`/github/connections/${encodeURIComponent(setupId)}/compare`, reqBody, signal, 'POST', 60000);
      let comparison = initial;
      if (initial?.diffId && ['QUEUED', 'RUNNING', 'PENDING'].includes(String(initial.status || '').toUpperCase())) {
        for (let attempt = 0; attempt < 24 && !signal.aborted; attempt++) {
          await new Promise(r => setTimeout(r, 450));
          comparison = await api(`/github/comparisons/${encodeURIComponent(initial.diffId)}`, undefined, signal, 'GET', 15000);
          if (!['QUEUED', 'RUNNING', 'PENDING'].includes(String(comparison?.status || '').toUpperCase())) break;
        }
      }
      let checks = null;
      if (target.pullNumber) {
        checks = await api(`/github/connections/${encodeURIComponent(setupId)}/pulls/${target.pullNumber}/checks`, undefined, signal, 'GET', 10000).catch(() => null);
      }
      return { comparison, checks, target, setupId };
    }, [], [], undefined, (body, payload) => {
      const { comparison, checks } = payload;
      const score = M.formatScoreV2Delta ? M.formatScoreV2Delta(comparison?.scoreV2Delta) : { available: false, deltaText: 'Unavailable', tone: 'neutral', dimensions: [] };
      const vDelta = comparison?.violationsDelta || {};
      const gDelta = comparison?.capabilityGapsDelta || {};

      const header = el('div', 'gh-pr-cmp-head');
      const verdictStr = String(comparison?.verdict || comparison?.status || 'COMPLETE').toUpperCase();
      const verdictCls = /PASS|IMPROVED|CLEAN|COMPLETE/.test(verdictStr) ? 'good' : /REGRESS|FAIL|BLOCK/.test(verdictStr) ? 'bad' : 'warn';
      append(header,
        el('span', 'badge ' + verdictCls, verdictStr),
        el('strong', '', `${comparison?.baseRef || target.baseRef || 'base'} → ${comparison?.headRef || target.headRef || 'head'}`),
        comparison?.trustLabel ? el('span', 'badge', comparison.trustLabel) : null
      );
      body.append(header);

      const kpi = el('div', 'gh-pr-kpi-grid');
      const scoreTile = el('div', 'gh-pr-kpi-tile');
      scoreTile.append(
        el('span', 'small muted', 'Health Score v2 Delta'),
        el('strong', 'gh-kpi-val is-' + score.tone, score.available ? `${score.beforeText} → ${score.afterText} (${score.deltaText})` : 'Unavailable')
      );
      const violTile = el('div', 'gh-pr-kpi-tile');
      const introduced = Number(vDelta.introduced ?? vDelta.added ?? 0);
      const resolved = Number(vDelta.resolved ?? vDelta.removed ?? 0);
      violTile.append(
        el('span', 'small muted', 'Policy Violations'),
        el('strong', 'gh-kpi-val ' + (introduced > 0 ? 'is-bad' : resolved > 0 ? 'is-good' : 'is-neutral'),
          `-${resolved} resolved · +${introduced} introduced`)
      );
      const gapTile = el('div', 'gh-pr-kpi-tile');
      const gapDeltaNum = Number(gDelta.delta ?? ((gDelta.after ?? 0) - (gDelta.before ?? 0)));
      gapTile.append(
        el('span', 'small muted', 'Capability Gaps'),
        el('strong', 'gh-kpi-val ' + (gapDeltaNum > 0 ? 'is-warn' : 'is-neutral'),
          gDelta.before != null && gDelta.after != null ? `${gDelta.before} → ${gDelta.after} (${gapDeltaNum > 0 ? '+' : ''}${gapDeltaNum})` : `${gapDeltaNum > 0 ? '+' : ''}${gapDeltaNum}`)
      );
      kpi.append(scoreTile, violTile, gapTile);
      body.append(kpi);

      if (comparison?.summary) {
        body.append(el('p', 'small', comparison.summary));
      }
      const overlapCard = window.EvoCommunityAdvisory?.renderPrCommunityOverlapCard?.(comparison?.communityOverlap);
      if (overlapCard) {
        body.append(overlapCard);
      }

      if (checks && Array.isArray(checks.items || checks.checks) && (checks.items || checks.checks).length) {
        const list = el('ul', 'dependency-path');
        for (const c of (checks.items || checks.checks).slice(0, 12)) {
          list.append(el('li', '', `${c.name || c.context}: ${c.conclusion || c.status || 'unknown'}`));
        }
        body.append(el('h3', '', 'GitHub PR Checks'), list);
      }

      if (target.pullNumber) {
        const reviewBox = el('section', 'gh-pr-review-gate');
        const heading = el('h3', '', `Post Architecture Review Comment to PR #${target.pullNumber}`);
        const note = el('p', 'small muted', 'Review and edit the comment below. Nothing is posted to GitHub until you confirm.');
        const textarea = el('textarea', 'gh-pr-review-body');
        textarea.rows = 6;
        textarea.value = comparison?.reviewMarkdown || comparison?.markdownReport ||
          `### Evo Architecture Comparison (PR #${target.pullNumber})\n\n- **Score v2:** ${score.available ? `${score.beforeText} -> ${score.afterText} (${score.deltaText})` : 'Unavailable'}\n- **Violations:** -${resolved} resolved, +${introduced} introduced\n- **Capability gaps:** ${gDelta.before ?? '?'} -> ${gDelta.after ?? '?'}\n`;
        const confirmCheck = el('input');
        confirmCheck.type = 'checkbox';
        const confirmLabel = append(el('label', 'gh-confirm-check'), confirmCheck,
          el('span', '', `I confirm posting this review comment to GitHub PR #${target.pullNumber}`));
        const postStatus = el('p', 'small muted');
        postStatus.setAttribute('role', 'status');
        const postBtn = btn(`Confirm & Post Comment to PR #${target.pullNumber}`, async () => {
          postBtn.disabled = true;
          postStatus.textContent = 'Posting review comment to GitHub…';
          try {
            const posted = await api(`/github/connections/${encodeURIComponent(setupId)}/pulls/${target.pullNumber}/review-comment`, {
              bodyMarkdown: textarea.value,
              confirmedByUser: true,
              diffId: comparison?.diffId || null
            }, undefined, 'POST', 20000);
            postStatus.textContent = posted?.url ? `Review comment posted: ${posted.url}` : 'Review comment posted to GitHub.';
            window.EvoToast?.push?.({ kind: 'ok', title: `Comment posted to PR #${target.pullNumber}` });
          } catch (err) {
            postStatus.textContent = err.message || 'Could not post comment.';
            postBtn.disabled = false;
          }
        }, 'button primary');
        postBtn.disabled = true;
        confirmCheck.addEventListener('change', () => { postBtn.disabled = !confirmCheck.checked; });
        reviewBox.append(heading, note, textarea, confirmLabel, postBtn, postStatus);
        body.append(reviewBox);
      }
    });
  }

  /* ── 4. Clean Pipeline Stepper & Gap Strip No-Ops (UI Streamlined) ──────── */
  function syncPipelineStepper() {
    const stepper = $('job-stage-stepper');
    if (stepper) {
      stepper.hidden = true;
      stepper.replaceChildren();
    }
  }

  async function syncPartialGapStrip() {
    const strip = $('job-gap-action-strip');
    if (strip) {
      strip.hidden = true;
      strip.replaceChildren();
    }
  }

  /* ── 5. Vercel / SonarCloud-Style GitHub Repo & Org Browser in Connect Dialog ── */
  function isRepoBrowserAvailable() {
    const caps = getAppState()?.capabilities || {};
    return Boolean(caps.githubEnterpriseEnabled || caps.multiUserAuthEnabled || caps.oauthWebEnabled || caps.deviceFlowEnabled);
  }

  function syncConnectTabsVisibility() {
    const tabBar = $('connect-mode-tabs');
    const form = $('connect-form');
    const browser = $('connect-repo-browser');
    if (!tabBar || !form || !browser) return;
    const avail = isRepoBrowserAvailable();
    tabBar.hidden = !avail;
    if (!avail) {
      hubState.browser.mode = 'manual';
      form.hidden = false;
      browser.hidden = true;
      return;
    }
    const isGhTab = hubState.browser.mode === 'github';
    form.hidden = isGhTab;
    browser.hidden = !isGhTab;
    for (const t of tabBar.querySelectorAll('[data-connect-tab]')) {
      const active = t.dataset.connectTab === hubState.browser.mode;
      t.classList.toggle('is-active', active);
      t.setAttribute('aria-selected', String(active));
    }
    if (isGhTab && $('connect-dialog')?.open) void renderRepoBrowser();
  }

  function setConnectTab(mode) {
    hubState.browser.mode = mode === 'github' ? 'github' : 'manual';
    syncConnectTabsVisibility();
  }

  function openConnectRepoBrowser(orgLogin = '') {
    const dialog = $('connect-dialog');
    if (!dialog) return;
    hubState.browser.org = String(orgLogin || '');
    hubState.browser.page = 1;
    hubState.browser.repos = [];
    hubState.browser.mode = 'github';
    const title = $('connect-title');
    if (title) title.textContent = 'Import a GitHub repository';
    const err = $('connect-error');
    if (err) err.hidden = true;
    if (!dialog.open) dialog.showModal();
    syncConnectTabsVisibility();
  }

  async function renderRepoBrowser() {
    const host = $('connect-repo-browser');
    if (!host) return;
    const isSignedIn = Boolean(window.EvoGitHubAuth?.isAuthenticated?.() || hubState.authStatus?.authenticated);
    if (!isSignedIn) {
      const emptyBox = el('div', 'gh-browser-signin');
      emptyBox.append(
        el('strong', '', 'Sign in with GitHub to browse your repositories & organizations'),
        el('p', 'small muted', 'Access public and private repositories across your personal account and GitHub organizations with 1-click Import & Analyze.'),
        append(el('div', 'rs-action-row'),
          btn('Sign in with GitHub →', () => {
            $('connect-dialog')?.close();
            window.EvoGitHubAuth?.openSignInModal?.();
          }, 'button primary'),
          btn('Or paste a URL / local path', () => setConnectTab('manual'), 'button subtle')
        )
      );
      host.replaceChildren(emptyBox);
      return;
    }

    const user = window.EvoGitHubAuth?.getUser?.();
    const toolbar = el('div', 'gh-browser-toolbar');
    const orgSel = el('select', 'rs-select gh-browser-org');
    orgSel.id = 'gh-browser-org-select';
    orgSel.setAttribute('aria-label', 'Filter by GitHub organization');
    orgSel.append(new Option(user?.login ? `Personal (@${user.login})` : 'All accessible repos', ''));
    for (const o of hubState.browser.orgs) {
      const login = typeof o === 'string' ? o : o.login;
      if (login) orgSel.append(new Option(`Org: ${login}`, login));
    }
    orgSel.value = hubState.browser.org;

    const searchInput = el('input', 'rs-input gh-browser-search');
    searchInput.id = 'gh-browser-search';
    searchInput.type = 'search';
    searchInput.placeholder = 'Search repositories by name…';
    searchInput.value = hubState.browser.q;

    const visRow = el('div', 'gh-vis-pills');
    visRow.setAttribute('role', 'group');
    visRow.setAttribute('aria-label', 'Repository visibility filter');
    for (const [val, lbl] of [['all', 'All'], ['public', 'Public'], ['private', 'Private']]) {
      const chip = btn(lbl, () => {
        hubState.browser.visibility = val;
        hubState.browser.page = 1;
        for (const c of visRow.children) c.classList.toggle('active', c.dataset.vis === val);
        void loadReposPage(false);
      }, 'chip' + (hubState.browser.visibility === val ? ' active' : ''));
      chip.dataset.vis = val;
      visRow.append(chip);
    }

    toolbar.append(orgSel, searchInput, visRow);
    const statusLine = el('p', 'small muted gh-browser-status');
    statusLine.setAttribute('role', 'status');
    const listBox = el('div', 'gh-browser-list');
    const footer = el('div', 'gh-browser-footer');
    host.replaceChildren(toolbar, statusLine, listBox, footer);

    let searchTimer = null;
    searchInput.addEventListener('input', () => {
      hubState.browser.q = searchInput.value.trim();
      clearTimeout(searchTimer);
      searchTimer = setTimeout(() => {
        hubState.browser.page = 1;
        void loadReposPage(false);
      }, 220);
    });
    orgSel.addEventListener('change', () => {
      hubState.browser.org = orgSel.value;
      hubState.browser.page = 1;
      void loadReposPage(false);
    });

    const api = getApi();
    if (!hubState.browser.loadedOrgs && api) {
      hubState.browser.loadedOrgs = true;
      api('/github/orgs', undefined, undefined, 'GET', 10000).then(res => {
        const items = Array.isArray(res?.items) ? res.items : [];
        hubState.browser.orgs = items;
        const cur = orgSel.value;
        orgSel.replaceChildren(new Option(user?.login ? `Personal (@${user.login})` : 'All accessible repos', ''));
        for (const o of items) {
          if (o?.login) orgSel.append(new Option(`Org: ${o.login}`, o.login));
        }
        if ([...orgSel.options].some(opt => opt.value === cur)) orgSel.value = cur;
      }).catch(() => {});
    }

    async function loadReposPage(appendMore) {
      if (!api) return;
      hubState.repoBrowserController?.abort();
      const controller = new AbortController();
      hubState.repoBrowserController = controller;
      if (!appendMore) {
        hubState.browser.page = 1;
        listBox.replaceChildren(el('p', 'small muted', 'Loading GitHub repositories…'));
      }
      statusLine.textContent = 'Fetching repositories from GitHub…';
      try {
        const qs = new URLSearchParams();
        if (hubState.browser.q) qs.set('q', hubState.browser.q);
        if (hubState.browser.org) qs.set('org', hubState.browser.org);
        if (hubState.browser.visibility && hubState.browser.visibility !== 'all') qs.set('visibility', hubState.browser.visibility);
        qs.set('sort', hubState.browser.sort || 'updated');
        qs.set('page', String(hubState.browser.page));
        const res = await api(`/github/user/repos?${qs.toString()}`, undefined, controller.signal, 'GET', 15000);
        if (controller.signal.aborted) return;
        const items = Array.isArray(res?.items) ? res.items : [];
        hubState.browser.repos = appendMore ? [...hubState.browser.repos, ...items] : items;
        hubState.browser.hasMore = Boolean(res?.hasMore);
        statusLine.textContent = `${hubState.browser.repos.length} repositor${hubState.browser.repos.length === 1 ? 'y' : 'ies'} loaded` + (hubState.browser.org ? ` in ${hubState.browser.org}` : '');
        renderRepoCards();
      } catch (err) {
        if (!controller.signal.aborted) {
          statusLine.textContent = err.message || 'Could not load GitHub repositories.';
          listBox.replaceChildren(el('p', 'form-error', err.message || 'Could not list repositories.'));
        }
      }
    }

    function renderRepoCards() {
      listBox.replaceChildren();
      if (!hubState.browser.repos.length) {
        listBox.append(el('p', 'small muted', 'No matching repositories found. Adjust search or visibility filters.'));
        footer.replaceChildren();
        return;
      }
      for (const repo of hubState.browser.repos) {
        const card = el('article', 'gh-repo-card');
        const info = el('div', 'gh-repo-card-info');
        const permLabel = repo.permissions?.admin ? 'Admin' : repo.permissions?.push ? 'Write' : 'Read';
        const head = append(el('div', 'gh-repo-card-head'),
          el('strong', 'gh-repo-card-name', repo.fullName || `${repo.owner}/${repo.name}`),
          el('span', 'badge ' + (repo.privateRepo ? 'warn' : ''), repo.privateRepo ? 'Private' : 'Public'),
          repo.language ? el('span', 'badge', repo.language) : null,
          el('span', 'badge', permLabel)
        );
        const desc = repo.description ? el('p', 'small muted gh-repo-card-desc', repo.description) : null;
        const meta = el('div', 'small muted', [
          repo.defaultBranch ? `Branch: ${repo.defaultBranch}` : null,
          repo.pushedAt ? `Updated ${M.formatRelativeTime(repo.pushedAt)}` : null,
          repo.stargazersCount ? `★ ${Number(repo.stargazersCount).toLocaleString()}` : null
        ].filter(Boolean).join(' · '));
        append(info, head, desc, meta);

        const importBtn = btn('Import & Analyze', async () => {
          importBtn.disabled = true;
          importBtn.textContent = 'Importing…';
          try {
            const sourceUrl = repo.cloneUrl || repo.htmlUrl || `https://github.com/${repo.fullName}`;
            await window.EvoApp?.connectAndAnalyze?.(sourceUrl, repo.defaultBranch || '');
            $('connect-dialog')?.close();
          } catch (err) {
            statusLine.textContent = err.message || 'Import failed.';
            importBtn.disabled = false;
            importBtn.textContent = 'Import & Analyze';
          }
        }, 'button primary gh-import-btn');
        importBtn.dataset.repoFullName = repo.fullName || '';

        card.append(info, importBtn);
        listBox.append(card);
      }
      footer.replaceChildren();
      if (hubState.browser.hasMore) {
        footer.append(btn('Load more repositories', () => {
          hubState.browser.page += 1;
          void loadReposPage(true);
        }, 'button subtle'));
      }
    }

    await loadReposPage(false);
  }

  function wireConnectModeTabs() {
    const tabBar = $('connect-mode-tabs');
    if (!tabBar) return;
    for (const t of tabBar.querySelectorAll('[data-connect-tab]')) {
      t.addEventListener('click', () => setConnectTab(t.dataset.connectTab));
    }
    $('connect-dialog')?.addEventListener('close', () => {
      hubState.repoBrowserController?.abort();
    });
  }

  function clearCaches() {
    hubState.refsController?.abort();
    hubState.repoHubController?.abort();
    hubState.repoBrowserController?.abort();
    hubState.authStatus = null;
    hubState.repoMetaBySetup.clear();
    hubState.selectedRefBySetup.clear();
    hubState.selectedPrBySetup.clear();
    hubState.browser.mode = 'manual';
    hubState.browser.orgs = [];
    hubState.browser.repos = [];
    hubState.browser.loadedOrgs = false;
    hubState.browser.org = '';
    hubState.browser.q = '';
    hubState.browser.page = 1;
    for (const id of ['connect-repo-browser', 'connect-smart-preview', 'connect-ref-select', 'github-hub-bar']) {
      const node = $(id);
      if (node) { node.replaceChildren(); if (id !== 'connect-ref-select') node.hidden = true; }
    }
    const refStatus = $('connect-ref-status');
    if (refStatus) refStatus.textContent = '';
    const srcInput = $('repository-source');
    if (srcInput) srcInput.value = '';
  }

  function init() {
    wireSessionPatDialog();
    wireConnectSmartIntake();
    wireConnectModeTabs();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  window.EvoGitHubHub = {
    refreshAuthStatus,
    openSessionPatDialog,
    getConnectSubmissionOptions,
    setSelectedRef,
    getSelectedRef,
    getRepoMetadata: setupId => hubState.repoMetaBySetup.get(setupId) || null,
    syncConnectedRepoBar,
    openPrComparisonModal,
    openConnectRepoBrowser,
    syncConnectTabsVisibility,
    setConnectTab,
    syncPipelineStepper,
    syncPartialGapStrip,
    isGitHubConnection,
    clearCaches
  };
})();
