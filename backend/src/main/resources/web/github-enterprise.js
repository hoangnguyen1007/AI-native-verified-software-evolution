/* Enterprise GitHub Hub: Enriched PRs & Quality Gate, Diff-Anchored Inline PR Review
 * with GitHub Suggestions, Commit Status / Check Runs, SARIF 2.1.0 Export & Code Scanning Upload,
 * 2-Step Confirmed GitHub Issues, CI Workflow Generator, Webhooks & /evo Deliveries,
 * CODEOWNERS Badges & Safe GitHub Permalinks.
 * Strict zero-XSS DOM construction; all external hrefs validated via EvoModel.isSafeHttpUrl. */
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

  const codeownersCache = new Map(); // graphId -> { found, sourcePath, ruleCount, rules }
  const drawerState = {
    open: false,
    setupId: null,
    activeTab: 'pulls',
    controller: null,
    reviewPreviewByPr: new Map()
  };

  function getApi() { return window.EvoApp?.api; }
  function getAppState() { return window.EvoApp?.state; }

  /* ── 1. CODEOWNERS Cache & Badges ───────────────────────────────────────── */
  async function loadCodeowners(graphId) {
    if (!graphId) return null;
    if (codeownersCache.has(graphId)) return codeownersCache.get(graphId);
    const api = getApi();
    const caps = getAppState()?.capabilities || {};
    if (!api || !caps.githubEnterpriseEnabled) return null;
    try {
      const data = await api(`/graphs/${graphId}/codeowners`, undefined, undefined, 'GET', 8000);
      const record = {
        found: Boolean(data?.found),
        sourcePath: data?.sourcePath || '.github/CODEOWNERS',
        ruleCount: Number(data?.ruleCount ?? data?.rules?.length ?? 0),
        rules: Array.isArray(data?.rules) ? data.rules : []
      };
      codeownersCache.set(graphId, record);
      return record;
    } catch (_) {
      const empty = { found: false, sourcePath: '', ruleCount: 0, rules: [] };
      codeownersCache.set(graphId, empty);
      return empty;
    }
  }

  function clearCaches() {
    codeownersCache.clear();
    drawerState.reviewPreviewByPr.clear();
    closeEnterpriseHub();
  }

  function ownersForPath(graphId, filePath) {
    if (!graphId || !filePath || !M?.matchCodeownersForPath) return [];
    const rec = codeownersCache.get(graphId);
    if (!rec?.found || !rec.rules?.length) return [];
    return M.matchCodeownersForPath(rec.rules, filePath);
  }

  function makeOwnerPills(owners, max = 3) {
    const list = Array.isArray(owners) ? owners.filter(Boolean) : [];
    if (!list.length) return null;
    const wrap = el('span', 'gh-owner-pills');
    wrap.setAttribute('aria-label', 'Code owners');
    for (const owner of list.slice(0, max)) {
      const pill = el('span', 'gh-owner-pill', owner);
      pill.title = `CODEOWNERS: ${owner}`;
      wrap.append(pill);
    }
    if (list.length > max) {
      wrap.append(el('span', 'gh-owner-pill is-more', `+${list.length - max}`));
    }
    return wrap;
  }

  /* ── 2. Safe GitHub Permalinks ("View on GitHub ↗") ─────────────────────── */
  function resolveActiveGitHubCoords(setupId = null) {
    const st = getAppState();
    const sid = setupId || st?.setup;
    if (!sid) return null;
    const meta = window.EvoGitHubHub?.getRepoMetadata?.(sid);
    if (meta && meta.owner && meta.repo) {
      return {
        host: meta.host || 'github.com',
        owner: meta.owner,
        repo: meta.repo,
        commitSha: meta.headSha || meta.commitSha || '',
        ref: window.EvoGitHubHub?.getSelectedRef?.(sid) || meta.defaultBranch || 'main'
      };
    }
    const conn = (st?.connections || []).find(c => c.setup === sid);
    const setupRec = (st?.setups || []).find(s => s.id === sid);
    const rawCoord = String(
      conn?.repository?.canonicalCoordinate ||
      conn?.source ||
      conn?.url ||
      setupRec?.repository?.canonicalCoordinate ||
      setupRec?.repository ||
      ''
    );
    const parsed = M?.parseRepositoryInput ? M.parseRepositoryInput(rawCoord) : null;
    if (parsed && ['GITHUB_REPO', 'GITHUB_PR'].includes(parsed.kind) && parsed.owner && parsed.repo) {
      return {
        host: 'github.com',
        owner: parsed.owner,
        repo: parsed.repo,
        commitSha: '',
        ref: window.EvoGitHubHub?.getSelectedRef?.(sid) || parsed.ref || 'main'
      };
    }
    return null;
  }

  function permalinkFor(filePath, startLine = null, endLine = null, explicitPermalink = null, setupId = null) {
    if (explicitPermalink && M?.isSafeHttpUrl?.(explicitPermalink)) return explicitPermalink;
    if (!filePath || !M?.buildGitHubPermalink) return null;
    const coords = resolveActiveGitHubCoords(setupId);
    if (!coords) return null;
    const url = M.buildGitHubPermalink({
      host: coords.host,
      owner: coords.owner,
      repo: coords.repo,
      commitSha: coords.commitSha,
      ref: coords.ref,
      path: filePath,
      startLine,
      endLine
    });
    return url && M?.isSafeHttpUrl?.(url) ? url : null;
  }

  function makePermalinkAnchor(url, label = 'View on GitHub ↗', cls = 'gh-permalink-link') {
    if (!url || !M?.isSafeHttpUrl?.(url)) return null;
    const a = el('a', cls, label);
    a.href = url;
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    return a;
  }

  /* ── 3. Helper: Extract ```suggestion blocks safely from comment body ───── */
  function renderCommentBodyWithSuggestion(bodyText, explicitSuggestion) {
    const wrap = el('div', 'gh-comment-body');
    const raw = String(bodyText || '');
    const match = raw.match(/```suggestion\r?\n([\s\S]*?)```/);
    if (match) {
      const before = raw.slice(0, match.index).trim();
      const after = raw.slice(match.index + match[0].length).trim();
      if (before) wrap.append(el('p', 'small', before));
      const sugBox = el('div', 'gh-suggestion-box');
      sugBox.append(
        el('div', 'gh-suggestion-head', 'Suggested change (GitHub 1-click commit)'),
        el('pre', 'gh-suggestion-code', match[1].replace(/\n$/, ''))
      );
      wrap.append(sugBox);
      if (after) wrap.append(el('p', 'small muted', after));
      return wrap;
    }
    if (raw) wrap.append(el('p', 'small', raw));
    if (explicitSuggestion) {
      const sugBox = el('div', 'gh-suggestion-box');
      sugBox.append(
        el('div', 'gh-suggestion-head', 'Suggested change (GitHub 1-click commit)'),
        el('pre', 'gh-suggestion-code', String(explicitSuggestion))
      );
      wrap.append(sugBox);
    }
    return wrap;
  }

  /* ── 4. Enterprise GitHub Hub Drawer (#github-enterprise-drawer) ────────── */
  function closeEnterpriseHub() {
    drawerState.controller?.abort();
    drawerState.controller = null;
    drawerState.open = false;
    const host = $('github-enterprise-drawer');
    if (host) {
      host.hidden = true;
      host.replaceChildren();
    }
  }

  function openEnterpriseHub(setupId, initialTab = 'pulls') {
    const host = $('github-enterprise-drawer');
    if (!host || !setupId) return;
    drawerState.open = true;
    drawerState.setupId = setupId;
    drawerState.activeTab = initialTab || 'pulls';
    host.hidden = false;
    renderDrawer();
    host.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' });
  }

  function renderDrawer() {
    const host = $('github-enterprise-drawer');
    if (!host || !drawerState.open || !drawerState.setupId) return;
    drawerState.controller?.abort();
    const controller = new AbortController();
    drawerState.controller = controller;
    const { signal } = controller;
    const setupId = drawerState.setupId;
    const meta = window.EvoGitHubHub?.getRepoMetadata?.(setupId);

    const shell = el('section', 'gh-ent-shell');
    shell.setAttribute('aria-label', 'Enterprise GitHub Hub');

    const header = el('header', 'gh-ent-header');
    const titleWrap = append(el('div', 'gh-ent-title-wrap'),
      el('h3', 'gh-ent-title', 'Enterprise GitHub Hub'),
      meta?.fullName ? el('span', 'gh-repo-pill', meta.fullName) : null
    );
    const closeBtn = btn('×', closeEnterpriseHub, 'icon-button gh-ent-close');
    closeBtn.setAttribute('aria-label', 'Close Enterprise GitHub Hub');
    header.append(titleWrap, closeBtn);

    const tabBar = el('div', 'gh-ent-tabs');
    tabBar.setAttribute('role', 'tablist');
    const tabDefs = [
      ['pulls', 'PRs & Quality Gate'],
      ['sarif', 'Security & SARIF'],
      ['ci', 'CI Workflow'],
      ['webhooks', 'Webhooks & /evo']
    ];
    for (const [id, lbl] of tabDefs) {
      const b = btn(lbl, () => {
        drawerState.activeTab = id;
        renderDrawer();
      }, 'gh-ent-tab' + (drawerState.activeTab === id ? ' is-active' : ''));
      b.dataset.entTab = id;
      b.setAttribute('role', 'tab');
      b.setAttribute('aria-selected', String(drawerState.activeTab === id));
      tabBar.append(b);
    }

    const body = el('div', 'gh-ent-body');
    shell.append(header, tabBar, body);
    host.replaceChildren(shell);

    if (drawerState.activeTab === 'pulls') void renderPullsTab(body, setupId, signal);
    else if (drawerState.activeTab === 'sarif') void renderSarifTab(body, setupId, signal);
    else if (drawerState.activeTab === 'ci') void renderCiWorkflowTab(body, setupId, signal);
    else void renderWebhooksTab(body, setupId, signal);
  }

  /* ── Tab 1: Enriched PRs, Quality Gate, Inline PR Review & Commit Status ── */
  async function renderPullsTab(body, setupId, signal) {
    const api = getApi();
    body.replaceChildren(el('p', 'small muted', 'Loading enriched pull requests & quality gates…'));
    let pulls = [];
    try {
      const res = await api(`/github/connections/${encodeURIComponent(setupId)}/pulls/enriched?state=open`, undefined, signal, 'GET', 15000)
        .catch(() => api(`/github/connections/${encodeURIComponent(setupId)}/pulls`, undefined, signal, 'GET', 15000));
      if (signal.aborted) return;
      pulls = Array.isArray(res?.items) ? res.items : (Array.isArray(res?.pulls) ? res.pulls : []);
    } catch (err) {
      if (signal.aborted) return;
      body.replaceChildren(el('p', 'form-error', err.message || 'Could not load pull requests.'));
      return;
    }

    const previewSlot = el('div', 'gh-ent-review-slot');
    previewSlot.id = 'gh-inline-review-preview-slot';

    if (!pulls.length) {
      body.replaceChildren(el('p', 'small muted', 'No open pull requests found for this repository.'));
      return;
    }

    const list = el('div', 'gh-pr-list');
    for (const pr of pulls.slice(0, 30)) {
      const card = el('article', 'gh-pr-card');
      const gate = M?.formatQualityGateBadge ? M.formatQualityGateBadge(pr.qualityGate || pr.qualityGateStatus || pr.gateStatus) : { label: 'Gate Pending', tone: 'muted' };
      const top = el('div', 'gh-pr-card-top');
      const authorName = pr.author?.login || pr.user?.login || pr.authorLogin || '';
      const monogram = M?.sanitizeAvatarMonogram ? M.sanitizeAvatarMonogram(authorName, pr.author?.name) : 'PR';
      const titleGroup = append(el('div', 'gh-pr-title-group'),
        el('span', 'gh-pr-avatar-mono', monogram),
        el('strong', 'gh-pr-num', `#${pr.number}`),
        el('span', 'gh-pr-title', pr.title || `Pull Request #${pr.number}`),
        pr.draft ? el('span', 'badge', 'Draft') : null
      );
      const ciVal = pr.ciState ?? pr.ciStatus;
      const scoreDeltaVal = pr.qualityGate?.scoreDelta ?? pr.scoreDelta;
      const badges = append(el('div', 'gh-pr-badges'),
        el('span', 'gh-gate-badge is-' + gate.tone, gate.label),
        ciVal ? el('span', 'badge', `CI: ${ciVal}`) : null,
        scoreDeltaVal != null ? el('span', 'badge ' + (Number(scoreDeltaVal) >= 0 ? 'good' : 'bad'), `Score ${Number(scoreDeltaVal) >= 0 ? '+' : ''}${Number(scoreDeltaVal).toFixed(1)}`) : null
      );
      top.append(titleGroup, badges);

      const headRef = pr.headRef || pr.head?.ref || `pull/${pr.number}/head`;
      const baseRef = pr.baseRef || pr.base?.ref || 'main';
      const metaParts = [
        `${headRef} → ${baseRef}`,
        authorName ? `by @${authorName}` : null,
        pr.changedFiles != null ? `${pr.changedFiles} files` : null,
        pr.updatedAt ? `updated ${M.formatRelativeTime(pr.updatedAt)}` : null
      ].filter(Boolean);
      const metaRow = el('div', 'gh-pr-meta small muted', metaParts.join(' · '));

      const actions = el('div', 'gh-pr-actions');
      append(actions,
        btn('Preview Inline PR Review', () => void loadInlineReviewPreview(previewSlot, setupId, pr, signal), 'button primary'),
        btn('Compare PR Diff', () => window.EvoGitHubHub?.openPrComparisonModal?.(setupId, {
          pullNumber: pr.number, baseRef, headRef, title: pr.title
        }), 'button subtle'),
        makePermalinkAnchor(pr.htmlUrl || pr.url, 'Open on GitHub ↗', 'button subtle gh-permalink-btn')
      );

      card.append(top, metaRow, actions);
      list.append(card);
    }

    body.replaceChildren(list, previewSlot);
  }

  async function loadInlineReviewPreview(slot, setupId, pr, signal) {
    const api = getApi();
    const appState = getAppState();
    slot.replaceChildren(el('p', 'small muted', `Building diff-anchored inline review preview for PR #${pr.number}…`));
    slot.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' });

    const activeReport = appState?.activePolicyReports?.get(appState.context) || appState?.reportIds?.[0] || null;
    try {
      const payload = {};
      if (appState?.graph) payload.graph = appState.graph;
      if (activeReport) payload.report = activeReport;
      const preview = await api(`/github/connections/${encodeURIComponent(setupId)}/pulls/${pr.number}/review/preview`, payload, signal, 'POST', 25000);
      if (signal.aborted) return;
      drawerState.reviewPreviewByPr.set(pr.number, preview);
      renderInlineReviewPreview(slot, setupId, pr, preview, signal);
    } catch (err) {
      if (!signal.aborted) {
        slot.replaceChildren(el('p', 'form-error', err.message || 'Could not generate inline PR review preview.'));
      }
    }
  }

  function renderInlineReviewPreview(slot, setupId, pr, preview, signal) {
    const api = getApi();
    const appState = getAppState();
    const panel = el('section', 'gh-review-preview-card');
    panel.dataset.prNumber = String(pr.number);

    const qg = preview.qualityGate || preview.qualityGateResult || {};
    const gateBadge = M?.formatQualityGateBadge ? M.formatQualityGateBadge(qg.verdict || qg.status || 'WARN') : { label: 'Quality Gate', tone: 'warn' };

    const head = el('div', 'gh-review-preview-head');
    head.append(
      append(el('div', 'gh-ent-title-wrap'),
        el('h4', '', `Inline PR Review Preview · #${pr.number}`),
        el('span', 'gh-gate-badge is-' + gateBadge.tone, gateBadge.label),
        preview.reviewPlanDigest ? el('span', 'small mono muted', `Plan ${M.shortDigest(preview.reviewPlanDigest)}`) : null
      ),
      btn('Close Preview', () => slot.replaceChildren(), 'button subtle')
    );
    panel.append(head);

    // Quality Gate Breakdown strip
    const qgConditions = Array.isArray(qg.conditions) ? qg.conditions : [];
    if (qg.summary || qgConditions.length) {
      const qgCard = el('div', 'gh-qg-card is-' + gateBadge.tone);
      if (qg.summary) qgCard.append(el('strong', '', qg.summary));
      if (qgConditions.length) {
        const ul = el('ul', 'gh-qg-cond-list');
        for (const c of qgConditions) {
          const st = String(c.status || (c.passed ? 'PASSED' : 'FAILED')).toUpperCase();
          const ok = c.status != null ? st === 'PASSED' : c.passed === true;
          ul.append(append(el('li', 'gh-qg-cond ' + (ok ? 'is-good' : 'is-bad')),
            el('span', '', ok ? '✓' : '✕'),
            el('span', '', `${c.metric || c.name}: ${c.actual ?? c.value ?? ''} (threshold: ${c.threshold ?? c.expected ?? ''})`)
          ));
        }
        qgCard.append(ul);
      }
      panel.append(qgCard);
    }

    // Inline diff-anchored comments
    const comments = Array.isArray(preview.comments) ? preview.comments
      : (Array.isArray(preview.inlineComments) ? preview.inlineComments : []);
    const commentsHead = el('h5', 'gh-subhead', `Diff-Anchored Inline Comments (${comments.length})`);
    panel.append(commentsHead);

    if (!comments.length) {
      panel.append(el('p', 'small muted', 'No line-anchored findings fall inside the PR diff hunks. A summary review comment will still be published.'));
    } else {
      const commentList = el('div', 'gh-inline-comments');
      for (const c of comments) {
        const cCard = el('article', 'gh-inline-comment');
        const sev = String(c.severity || 'WARN').toUpperCase();
        const sevCls = /ERR|CRIT|BLOCK|MUST/.test(sev) ? 'bad' : /WARN|MAY/.test(sev) ? 'warn' : 'good';
        const loc = `${c.path || 'file'}:${c.startLine && c.startLine !== c.line ? `${c.startLine}–${c.line}` : (c.line || 1)}`;
        const owners = Array.isArray(c.owners) && c.owners.length ? c.owners : ownersForPath(appState?.graph, c.path);
        const linkUrl = permalinkFor(c.path, c.startLine || c.line, c.line, c.permalink, setupId);

        const cTop = el('div', 'gh-inline-comment-head');
        append(cTop,
          el('span', 'badge ' + sevCls, sev),
          c.ruleId ? el('span', 'badge', c.ruleId) : null,
          el('code', 'gh-comment-loc', `${loc} (${c.side || 'RIGHT'})`),
          makeOwnerPills(owners),
          makePermalinkAnchor(linkUrl, 'View on GitHub ↗', 'gh-permalink-link')
        );
        cCard.append(cTop, renderCommentBodyWithSuggestion(c.body || c.message, c.suggestion));
        commentList.append(cCard);
      }
      panel.append(commentList);
    }

    // Editable Summary + Review Event + Commit Status + Explicit Confirmation
    const eventSel = el('select', 'rs-select');
    eventSel.setAttribute('aria-label', 'GitHub review event');
    eventSel.append(
      new Option('COMMENT (non-blocking review)', 'COMMENT'),
      new Option('REQUEST_CHANGES (block merge on gate failure)', 'REQUEST_CHANGES'),
      new Option('APPROVE (quality gate passed)', 'APPROVE')
    );
    eventSel.value = preview.suggestedEvent || preview.recommendedEvent || (gateBadge.tone === 'bad' ? 'REQUEST_CHANGES' : 'COMMENT');

    const bodyArea = el('textarea', 'rs-textarea mono');
    bodyArea.rows = 5;
    bodyArea.value = preview.bodyMarkdown || '';

    const statusCheck = el('input');
    statusCheck.type = 'checkbox';
    statusCheck.checked = true;
    const checkRunCheck = el('input');
    checkRunCheck.type = 'checkbox';
    checkRunCheck.checked = Boolean(preview.checkRunAvailable);

    const confirmCheck = el('input');
    confirmCheck.type = 'checkbox';
    confirmCheck.id = 'gh-review-publish-confirm';

    const publishStatus = el('div', 'gh-publish-status');
    publishStatus.setAttribute('role', 'status');

    const publishBtn = btn(`Confirm & Publish Inline PR Review (#${pr.number})`, async () => {
      publishBtn.disabled = true;
      publishStatus.replaceChildren(el('p', 'small muted', 'Publishing inline review & status to GitHub…'));
      try {
        const res = await api(`/github/connections/${encodeURIComponent(setupId)}/pulls/${pr.number}/review/publish`, {
          confirmationToken: preview.confirmationToken,
          reviewPlanDigest: preview.reviewPlanDigest,
          confirmedByUser: true,
          event: eventSel.value,
          bodyOverride: bodyArea.value,
          postCommitStatus: statusCheck.checked,
          postCheckRun: checkRunCheck.checked
        }, signal, 'POST', 25000);
        const receipt = el('div', 'rs-inline-notice is-good');
        append(receipt,
          el('strong', '', `PR Review Published (${res.state || eventSel.value})`),
          el('p', 'small', [
            `${Number(res.inlineCommentsPosted ?? comments.length)} inline comment${Number(res.inlineCommentsPosted ?? comments.length) === 1 ? '' : 's'} posted`,
            res.commitStatusPosted ? 'Commit Status posted' : null,
            res.checkRunId ? `Check Run #${res.checkRunId}` : null,
            res.qualityGateVerdict ? `Quality Gate: ${res.qualityGateVerdict}` : null
          ].filter(Boolean).join(' · ')),
          makePermalinkAnchor(res.htmlUrl || res.url, 'Open Published Review on GitHub ↗', 'button primary')
        );
        publishStatus.replaceChildren(receipt);
        window.EvoToast?.push?.({ kind: 'ok', title: `Inline review published to PR #${pr.number}` });
      } catch (err) {
        publishStatus.replaceChildren(el('p', 'form-error', err.message || 'Could not publish PR review.'));
        publishBtn.disabled = !confirmCheck.checked;
      }
    }, 'button primary');
    publishBtn.disabled = true;
    confirmCheck.addEventListener('change', () => { publishBtn.disabled = !confirmCheck.checked; });

    const headSha = pr.headSha || pr.head?.sha || preview.commitSha || '';
    const commitStatusBtn = headSha ? btn('Publish Commit Status Only', async () => {
      if (!confirmCheck.checked) {
        publishStatus.replaceChildren(el('p', 'small muted', 'Check the explicit confirmation box first.'));
        return;
      }
      commitStatusBtn.disabled = true;
      try {
        const stState = gateBadge.tone === 'bad' ? 'failure' : 'success';
        const stRes = await api(`/github/connections/${encodeURIComponent(setupId)}/commits/${encodeURIComponent(headSha)}/status`, {
          confirmedByUser: true,
          state: stState,
          context: 'evo/architecture-quality-gate',
          description: qg.summary || `Evo Quality Gate: ${gateBadge.label}`,
          createCheckRun: checkRunCheck.checked
        }, signal, 'POST', 15000);
        publishStatus.replaceChildren(el('div', 'rs-inline-notice is-good',
          `Commit status "${stRes?.state || stState}" published for ${headSha.slice(0, 10)}.`));
        window.EvoToast?.push?.({ kind: 'ok', title: 'GitHub Commit Status published' });
      } catch (err) {
        publishStatus.replaceChildren(el('p', 'form-error', err.message || 'Could not publish commit status.'));
      } finally {
        commitStatusBtn.disabled = false;
      }
    }, 'button subtle') : null;

    panel.append(
      append(el('label', 'rs-field'), el('span', 'small muted', 'Review Action'), eventSel),
      append(el('label', 'rs-field'), el('span', 'small muted', 'Review Summary Markdown'), bodyArea),
      append(el('div', 'gh-check-options'),
        append(el('label', 'gh-confirm-check'), statusCheck, el('span', '', 'Publish GitHub Commit Status (evo/architecture-quality-gate)')),
        append(el('label', 'gh-confirm-check'), checkRunCheck, el('span', '', 'Create GitHub Check Run with file annotations (GitHub App token)'))
      ),
      append(el('label', 'gh-confirm-check'), confirmCheck,
        el('span', '', `Explicit Confirmation: I confirm publishing this inline PR review (${comments.length} inline comment${comments.length === 1 ? '' : 's'}) to GitHub PR #${pr.number}.`)),
      append(el('div', 'rs-action-row'), publishBtn, commitStatusBtn),
      publishStatus
    );
    slot.replaceChildren(panel);
  }

  /* ── Tab 2: Security & SARIF 2.1.0 Export / GitHub Code Scanning Upload ─── */
  async function renderSarifTab(body, setupId, signal) {
    const api = getApi();
    const appState = getAppState();
    const graphId = appState?.graph;
    const reports = appState?.reportIds || [];
    const meta = window.EvoGitHubHub?.getRepoMetadata?.(setupId);

    const card = el('div', 'gh-ent-card');
    card.append(
      el('h4', '', 'SARIF 2.1.0 Architecture & Security Findings'),
      el('p', 'small muted', 'Export policy findings as OASIS SARIF 2.1.0 or upload directly to GitHub Code Scanning Alerts.')
    );

    if (!graphId || !reports.length) {
      card.append(el('div', 'rs-inline-notice is-warn', 'Run an analysis with policy rules first so a policy report is available for SARIF export.'));
      body.replaceChildren(card);
      return;
    }

    const reportSel = el('select', 'rs-select');
    reportSel.setAttribute('aria-label', 'Policy report for SARIF export');
    reports.forEach((rid, idx) => reportSel.append(new Option(`Policy Report ${idx + 1} (${M.shortDigest(rid)})`, rid)));
    const activeRep = appState?.activePolicyReports?.get(appState.context);
    if (activeRep && reports.includes(activeRep)) reportSel.value = activeRep;

    const statusBox = el('div', 'gh-publish-status');
    statusBox.setAttribute('role', 'status');

    const dlBtn = btn('Download SARIF 2.1.0 (.sarif)', async () => {
      dlBtn.disabled = true;
      statusBox.replaceChildren(el('p', 'small muted', 'Generating SARIF 2.1.0 document…'));
      try {
        const url = `/api/v1/graphs/${graphId}/reports/${reportSel.value}/sarif?download=1`;
        const resp = await fetch(url, { credentials: 'same-origin', signal });
        if (!resp.ok) throw new Error(`SARIF export failed (${resp.status})`);
        const text = await resp.text();
        window.EvoApp?.downloadBlob?.(new Blob([text], { type: 'application/sarif+json;charset=utf-8' }), 'evo-architecture-findings.sarif');
        let summary = 'SARIF 2.1.0 file downloaded.';
        try {
          const parsed = JSON.parse(text);
          const run = parsed.runs?.[0];
          const rulesCount = run?.tool?.driver?.rules?.length ?? 0;
          const resCount = run?.results?.length ?? 0;
          summary = `Downloaded SARIF 2.1.0 (${rulesCount} rules · ${resCount} findings).`;
        } catch (_) {}
        statusBox.replaceChildren(el('div', 'rs-inline-notice is-good', summary));
        window.EvoToast?.push?.({ kind: 'ok', title: 'SARIF 2.1.0 downloaded' });
      } catch (err) {
        if (!signal.aborted) statusBox.replaceChildren(el('p', 'form-error', err.message || 'Could not download SARIF.'));
      } finally {
        dlBtn.disabled = false;
      }
    }, 'button primary');

    const shaInput = el('input', 'rs-input mono');
    shaInput.value = meta?.headSha || meta?.commitSha || '0'.repeat(40);
    shaInput.placeholder = '40-character Git commit SHA';
    const refInput = el('input', 'rs-input mono');
    const selectedRef = window.EvoGitHubHub?.getSelectedRef?.(setupId) || meta?.defaultBranch || 'main';
    refInput.value = selectedRef.startsWith('refs/') ? selectedRef : `refs/heads/${selectedRef}`;
    refInput.placeholder = 'refs/heads/main';

    const confirmUpload = el('input');
    confirmUpload.type = 'checkbox';
    confirmUpload.id = 'gh-sarif-upload-confirm';

    const uploadBtn = btn('Confirm & Upload SARIF to GitHub Code Scanning', async () => {
      uploadBtn.disabled = true;
      statusBox.replaceChildren(el('p', 'small muted', 'Uploading gzipped SARIF to GitHub Code Scanning API…'));
      try {
        const res = await api(`/github/connections/${encodeURIComponent(setupId)}/code-scanning/sarif`, {
          graph: graphId,
          report: reportSel.value,
          commitSha: shaInput.value.trim(),
          ref: refInput.value.trim(),
          confirmedByUser: true
        }, signal, 'POST', 25000);
        const done = el('div', 'rs-inline-notice is-good');
        append(done,
          el('strong', '', 'SARIF Uploaded to GitHub Code Scanning'),
          el('p', 'small', `SARIF ID: ${res.sarifId || 'accepted'} · ${res.ruleCount ?? 0} rules · ${res.resultCount ?? 0} results`),
          res.sarifDigest ? el('p', 'small mono muted', `Digest: ${M.shortDigest(res.sarifDigest)}`) : null,
          makePermalinkAnchor(res.url, 'View Security Alerts on GitHub ↗', 'button subtle')
        );
        statusBox.replaceChildren(done);
        window.EvoToast?.push?.({ kind: 'ok', title: 'SARIF uploaded to GitHub Code Scanning' });
      } catch (err) {
        statusBox.replaceChildren(el('p', 'form-error', err.message || 'Could not upload SARIF.'));
        uploadBtn.disabled = !confirmUpload.checked;
      }
    }, 'button primary');
    uploadBtn.disabled = true;
    confirmUpload.addEventListener('change', () => { uploadBtn.disabled = !confirmUpload.checked; });

    card.append(
      append(el('label', 'rs-field'), el('span', 'small muted', 'Policy Report'), reportSel),
      append(el('div', 'rs-action-row'), dlBtn),
      el('hr', 'gh-divider'),
      el('h5', 'gh-subhead', 'Upload to GitHub Code Scanning (Security Tab)'),
      append(el('div', 'gh-two-col'),
        append(el('label', 'rs-field'), el('span', 'small muted', 'Commit SHA'), shaInput),
        append(el('label', 'rs-field'), el('span', 'small muted', 'Git Ref'), refInput)
      ),
      append(el('label', 'gh-confirm-check'), confirmUpload,
        el('span', '', 'Explicit Confirmation: I confirm uploading SARIF 2.1.0 architecture alerts to GitHub Code Scanning.')),
      append(el('div', 'rs-action-row'), uploadBtn),
      statusBox
    );
    body.replaceChildren(card);
  }

  /* ── Tab 3: GitHub Actions CI Workflow Generator ────────────────────────── */
  async function renderCiWorkflowTab(body, setupId, signal) {
    const api = getApi();
    const card = el('div', 'gh-ent-card');
    card.append(
      el('h4', '', 'GitHub Actions Architecture Gate Workflow'),
      el('p', 'small muted', 'Generate a ready-to-commit workflow that runs Evo analysis on Pull Requests, enforces your Quality Gate, and uploads SARIF 2.1.0.')
    );

    const failOnMustInput = el('input');
    failOnMustInput.type = 'checkbox';
    failOnMustInput.checked = true;
    const minScoreInput = el('input', 'rs-input gh-score-num');
    minScoreInput.type = 'number';
    minScoreInput.min = '0';
    minScoreInput.max = '100';
    minScoreInput.value = '60';

    const codeWrap = el('div', 'gh-ci-code-wrap');
    const controls = el('div', 'gh-ci-controls');
    controls.append(
      append(el('label', 'gh-confirm-check'), failOnMustInput, el('span', '', 'Fail CI on MUST policy violations')),
      append(el('label', 'gh-inline-field'), el('span', 'small muted', 'Min Score v2'), minScoreInput),
      btn('Regenerate YAML', () => void loadYaml(), 'button subtle')
    );

    async function loadYaml() {
      codeWrap.replaceChildren(el('p', 'small muted', 'Generating GitHub Actions workflow YAML…'));
      try {
        const qs = `?failOnMust=${failOnMustInput.checked}&minScore=${encodeURIComponent(minScoreInput.value || '60')}`;
        const data = await api(`/github/connections/${encodeURIComponent(setupId)}/ci-workflow${qs}`, undefined, signal, 'GET', 12000);
        if (signal.aborted) return;
        const filename = data.filename || '.github/workflows/evo-architecture-gate.yml';
        const yamlText = data.yaml || '';
        const metaRow = append(el('div', 'gh-ci-meta-row'),
          el('code', 'gh-comment-loc', filename),
          data.sha256 ? el('span', 'small mono muted', `SHA-256 ${String(data.sha256).slice(0, 12)}`) : null
        );
        const copyBtn = btn('Copy YAML', async () => {
          try {
            await navigator.clipboard?.writeText(yamlText);
            window.EvoCopyFx?.flash?.(copyBtn, 'Copied!', 'Copy YAML');
            window.EvoToast?.push?.({ kind: 'ok', title: 'CI Workflow YAML copied' });
          } catch (_) {}
        }, 'button primary');
        const dlBtn = btn('Download .yml', () => {
          window.EvoApp?.downloadBlob?.(new Blob([yamlText], { type: 'text/yaml;charset=utf-8' }), filename.split('/').at(-1) || 'evo-architecture-gate.yml');
        }, 'button subtle');
        codeWrap.replaceChildren(
          metaRow,
          append(el('div', 'rs-action-row'), copyBtn, dlBtn),
          el('pre', 'gh-yaml-pre', yamlText)
        );
      } catch (err) {
        if (!signal.aborted) codeWrap.replaceChildren(el('p', 'form-error', err.message || 'Could not generate CI workflow.'));
      }
    }

    card.append(controls, codeWrap);
    body.replaceChildren(card);
    await loadYaml();
  }

  /* ── Tab 4: Webhooks & /evo ChatOps Deliveries ──────────────────────────── */
  async function renderWebhooksTab(body, setupId, signal) {
    const api = getApi();
    const card = el('div', 'gh-ent-card');
    const headRow = el('div', 'gh-review-preview-head');
    headRow.append(
      el('h4', '', 'GitHub Webhooks & /evo ChatOps Deliveries'),
      btn('Refresh', () => void renderWebhooksTab(body, setupId, signal), 'button subtle')
    );

    const chatOpsBar = append(el('div', 'gh-chatops-ref'),
      el('span', 'small muted', 'Supported PR comment commands:'),
      el('code', 'gh-chatops-cmd', '/evo analyze'),
      el('code', 'gh-chatops-cmd', '/evo score'),
      el('code', 'gh-chatops-cmd', '/evo help')
    );

    const content = el('div');
    content.append(el('p', 'small muted', 'Loading recent HMAC-SHA256 webhook deliveries…'));
    card.append(headRow, chatOpsBar, content);
    body.replaceChildren(card);

    try {
      const data = await api('/github/webhooks/deliveries', undefined, signal, 'GET', 10000);
      if (signal.aborted) return;
      const configured = Boolean(data?.webhookConfigured);
      const statusBadge = el('span', 'badge ' + (configured ? 'good' : 'warn'),
        configured ? 'HMAC-SHA256 Webhook Secret Configured' : 'Webhook Secret Not Configured (Configure in Workspace Settings)');
      const items = Array.isArray(data?.items) ? data.items : [];
      if (!items.length) {
        content.replaceChildren(
          append(el('div', 'rs-verdict-badges'), statusBadge),
          el('p', 'small muted', 'No webhook deliveries recorded yet. Point your GitHub repo or App webhook at POST /api/v1/github/webhooks.')
        );
        return;
      }
      const table = el('table', 'comparison-table gh-webhook-table');
      const thead = append(el('thead'), append(el('tr'),
        ...['Delivery', 'Event', 'Repository', 'ChatOps', 'Signature', 'Status', 'Time'].map(h => Object.assign(el('th', '', h), { scope: 'col' }))
      ));
      const tbody = el('tbody');
      for (const d of items.slice(0, 40)) {
        const sigOk = d.signatureVerified === true;
        const stTone = /ACCEPTED|PROCESSED|QUEUED|OK/i.test(String(d.status || '')) ? 'good' : /REJECT|FAIL|DENY/i.test(String(d.status || '')) ? 'bad' : 'warn';
        const prNum = d.pullNumber ?? d.prNumber;
        tbody.append(append(el('tr'),
          el('td', 'mono small', String(d.deliveryId || '').slice(0, 14)),
          el('td', '', [d.event, d.action].filter(Boolean).join('.')),
          el('td', 'mono small', (d.repository || '') + (prNum ? ` #${prNum}` : '')),
          el('td', 'mono small', d.chatOpsCommand || '—'),
          append(el('td'), el('span', 'badge ' + (sigOk ? 'good' : 'bad'), sigOk ? 'Verified' : 'Invalid')),
          append(el('td'), el('span', 'badge ' + stTone, d.status || 'RECORDED')),
          el('td', 'small muted', M?.formatRelativeTime ? M.formatRelativeTime(d.receivedAtMs || d.receivedAt) : '')
        ));
      }
      table.append(thead, tbody);
      content.replaceChildren(
        append(el('div', 'rs-verdict-badges'), statusBadge, el('span', 'small muted', `${items.length} recent deliver${items.length === 1 ? 'y' : 'ies'}`)),
        append(el('div', 'table-scroll'), table)
      );
    } catch (err) {
      if (!signal.aborted) {
        content.replaceChildren(el('p', 'form-error', err.message || 'Could not load webhook deliveries.'));
      }
    }
  }

  /* ── 5. 2-Step Confirmed Create GitHub Issue Modal ──────────────────────── */
  async function openCreateIssueModal(setupId, opts = {}) {
    const api = getApi();
    const inspect = window.EvoApp?.inspect;
    const appState = getAppState();
    const sid = setupId || appState?.setup;
    if (!api || !inspect || !sid) return;

    return inspect('Create GitHub Issue', 'GITHUB ISSUE PLAN', async signal => {
      return api(`/github/connections/${encodeURIComponent(sid)}/issues/prepare`, {
        graph: opts.graph || appState?.graph || null,
        report: opts.report || null,
        findingId: opts.findingId || null,
        hotspotSymbol: opts.hotspotSymbol || null,
        title: opts.title || null,
        labels: opts.labels || null,
        assignees: opts.assignees || null
      }, signal, 'POST', 20000);
    }, [], [], undefined, (body, plan) => {
      const formWrap = el('div', 'gh-issue-modal');
      const owners = Array.isArray(plan.codeowners) ? plan.codeowners
        : Array.isArray(plan.owners) ? plan.owners
        : (opts.filePath ? ownersForPath(opts.graph || appState?.graph, opts.filePath) : []);

      const titleInput = el('input', 'rs-input');
      titleInput.id = 'gh-issue-title-input';
      titleInput.value = plan.title || opts.title || 'Architecture issue';

      const labelsInput = el('input', 'rs-input');
      labelsInput.value = Array.isArray(plan.labels) ? plan.labels.join(', ') : 'architecture, evo';

      const assigneesInput = el('input', 'rs-input');
      assigneesInput.value = Array.isArray(plan.assignees) ? plan.assignees.join(', ') : '';
      assigneesInput.placeholder = 'GitHub usernames (comma-separated, e.g. octocat)';

      const bodyInput = el('textarea', 'rs-textarea mono');
      bodyInput.rows = 8;
      bodyInput.value = plan.bodyMarkdown || '';

      const confirmCheck = el('input');
      confirmCheck.type = 'checkbox';
      confirmCheck.id = 'gh-issue-confirm-check';

      const statusBox = el('div', 'gh-publish-status');
      statusBox.setAttribute('role', 'status');

      const publishBtn = btn('Confirm & Create GitHub Issue', async () => {
        publishBtn.disabled = true;
        statusBox.replaceChildren(el('p', 'small muted', 'Creating issue on GitHub…'));
        try {
          const labelsOverride = labelsInput.value.split(',').map(s => s.trim()).filter(Boolean);
          const assigneesOverride = assigneesInput.value.split(',').map(s => s.trim().replace(/^@/, '')).filter(Boolean);
          const created = await api(`/github/connections/${encodeURIComponent(sid)}/issues/publish`, {
            confirmationToken: plan.confirmationToken,
            issuePlanDigest: plan.issuePlanDigest,
            confirmedByUser: true,
            titleOverride: titleInput.value.trim(),
            bodyOverride: bodyInput.value,
            labelsOverride,
            assigneesOverride
          }, undefined, 'POST', 20000);
          const done = el('div', 'rs-inline-notice is-good');
          append(done,
            el('strong', '', `GitHub Issue #${created.issueNumber || created.number || ''} Created`),
            el('p', 'small', created.title || titleInput.value.trim()),
            makePermalinkAnchor(created.htmlUrl || created.url, 'Open Issue on GitHub ↗', 'button primary')
          );
          statusBox.replaceChildren(done);
          window.EvoToast?.push?.({ kind: 'ok', title: `GitHub Issue #${created.issueNumber || ''} created` });
        } catch (err) {
          statusBox.replaceChildren(el('p', 'form-error', err.message || 'Could not create GitHub issue.'));
          publishBtn.disabled = !confirmCheck.checked;
        }
      }, 'button primary');
      publishBtn.disabled = true;
      confirmCheck.addEventListener('change', () => { publishBtn.disabled = !confirmCheck.checked; });

      append(formWrap,
        plan.issuePlanDigest ? el('p', 'small mono muted', `Issue plan digest: ${M.shortDigest(plan.issuePlanDigest)}`) : null,
        owners.length ? append(el('div', 'gh-owner-row'), el('span', 'small muted', 'Matched CODEOWNERS:'), makeOwnerPills(owners, 6)) : null,
        append(el('label', 'rs-field'), el('span', 'small muted', 'Issue Title'), titleInput),
        append(el('div', 'gh-two-col'),
          append(el('label', 'rs-field'), el('span', 'small muted', 'Labels (comma-separated)'), labelsInput),
          append(el('label', 'rs-field'), el('span', 'small muted', 'Assignees (comma-separated)'), assigneesInput)
        ),
        append(el('label', 'rs-field'), el('span', 'small muted', 'Issue Description (Markdown)'), bodyInput),
        append(el('label', 'gh-confirm-check'), confirmCheck,
          el('span', '', 'Explicit Confirmation: I confirm creating this issue on GitHub.')),
        append(el('div', 'rs-action-row'), publishBtn),
        statusBox
      );
      body.append(formWrap);
    });
  }

  window.EvoGitHubEnterprise = {
    openEnterpriseHub,
    closeEnterpriseHub,
    openCreateIssueModal,
    loadCodeowners,
    ownersForPath,
    makeOwnerPills,
    permalinkFor,
    makePermalinkAnchor,
    clearCaches
  };
})();
