/**
 * Evo UX Engine — High-Depth, Unified, Evidence-First Architecture
 * Manages:
 *  - 4 essential views: Overview, Architecture Map, Remediation & Evolution, Evidence & Gaps
 *  - Multi-repository switcher & real-time synchronization
 *  - Unified Remediation & Evolution workflow (Findings → Diagnosis → OpenRewrite Patch → Sandbox Verification → Evolution)
 *  - Direct backend API & DOM synchronization with zero fake demo data
 */
(() => {
  'use strict';

  const $ = id => document.getElementById(id);
  const qsa = (sel, ctx = document) => [...ctx.querySelectorAll(sel)];

  /* ── 1. View Navigation & Routing ─────────────────────────────────────── */
  const navs = qsa('.nav[data-view]');
  const views = qsa('.section');

  function go(key) {
    if (!key) key = 'overview';
    const targetSection = $('view-' + key);
    if (!targetSection) key = 'overview';

    // Switch active section
    views.forEach(el => {
      el.classList.toggle('active', el.id === 'view-' + key);
    });

    // Switch active sidebar nav button
    navs.forEach(btn => {
      const active = btn.dataset.view === key;
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-current', active ? 'page' : 'false');
    });

    // Scroll main container to top
    const mainEl = $('main');
    if (mainEl) mainEl.scrollTop = 0;
    window.scrollTo({ top: 0, behavior: 'instant' });

    // Update URL hash
    if (history.replaceState) {
      history.replaceState(null, '', '#' + key);
    }

    // Trigger matching app.js tab loader if available
    const tabMap = {
      overview: 'tab-overview',
      architecture: 'tab-graph',
      insights: 'tab-insights',
      remediation: 'tab-policy',
      evidence: 'tab-gaps'
    };
    const tabBtn = $(tabMap[key]);
    if (tabBtn && tabBtn.getAttribute('aria-selected') !== 'true') {
      try { tabBtn.click(); } catch (_) { }
    }

    // Trigger Cytoscape resize if entering architecture view
    if (key === 'architecture') {
      setTimeout(() => {
        const cyCanvas = document.querySelector('.graph-canvas');
        if (cyCanvas && window.cytoscape) {
          // If cytoscape instance exists, resize and fit
          const cy = cyCanvas._cy || window.EvoApp?.cy;
          if (cy) {
            try { cy.resize(); cy.fit(undefined, 32); } catch (_) { }
          }
        }
      }, 60);
    }
  }

  // Bind sidebar nav clicks
  navs.forEach(btn => {
    btn.addEventListener('click', () => go(btn.dataset.view));
  });

  // Bind [data-go] action buttons across views
  document.addEventListener('click', e => {
    const trigger = e.target.closest('[data-go]');
    if (trigger) {
      e.preventDefault();
      go(trigger.dataset.go);
    }
  });

  /* ── 2. Draggable Sidebar Resizer ─────────────────────────────────────── */
  function wireSidebarResizer() {
    const resizer = $('sidebar-resizer');
    if (!resizer) return;

    try {
      const savedWidth = localStorage.getItem('evo-sidebar-w');
      if (savedWidth) {
        document.documentElement.style.setProperty('--sidebar-w', savedWidth);
      }
    } catch (_) { }

    let isResizing = false;
    let startX = 0;
    let startWidth = 260;

    resizer.addEventListener('mousedown', e => {
      e.preventDefault();
      isResizing = true;
      startX = e.clientX;
      const currentW = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--sidebar-w')) || 260;
      startWidth = currentW;
      resizer.classList.add('resizing');
      document.body.style.cursor = 'col-resize';
      document.body.style.userSelect = 'none';
    });

    window.addEventListener('mousemove', e => {
      if (!isResizing) return;
      const delta = e.clientX - startX;
      const newWidth = Math.max(200, Math.min(500, startWidth + delta));
      document.documentElement.style.setProperty('--sidebar-w', newWidth + 'px');
    });

    window.addEventListener('mouseup', () => {
      if (!isResizing) return;
      isResizing = false;
      resizer.classList.remove('resizing');
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      try {
        const finalW = getComputedStyle(document.documentElement).getPropertyValue('--sidebar-w').trim();
        localStorage.setItem('evo-sidebar-w', finalW);
      } catch (_) { }
      if (window.EvoApp?.state?.cy) {
        try { window.EvoApp.state.cy.resize(); } catch (_) { }
      }
    });
  }

  /* ── 3. Multi-Repository & Dynamic Header Sync ────────────────────────── */
  function wireRepositorySync() {
    const repoTitleEl = $('repository-title');
    const topbarRepoName = $('topbar-repo-name');
    const topbarRepoKind = $('topbar-repo-kind');

    if (!repoTitleEl || !topbarRepoName) return;

    function syncRepo() {
      const title = repoTitleEl.textContent.trim();
      if (title && title !== 'Explore your architecture') {
        topbarRepoName.textContent = title;
        if (topbarRepoKind) {
          topbarRepoKind.textContent = 'Active';
          topbarRepoKind.hidden = false;
        }
      } else {
        topbarRepoName.textContent = 'Select repository';
        if (topbarRepoKind) topbarRepoKind.hidden = true;
      }
    }

    const obs = new MutationObserver(syncRepo);
    obs.observe(repoTitleEl, { childList: true, characterData: true, subtree: true });
    syncRepo();
  }

  /* ── 3. Theme Toggle & Persistence ────────────────────────────────────── */
  function applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('evo-theme', theme); } catch (_) { }
    const themeBtn = $('theme');
    if (themeBtn) themeBtn.textContent = theme === 'dark' ? '◐ Light' : '◐ Dark';
  }

  function toggleTheme() {
    const current = document.documentElement.dataset.theme || 'dark';
    const next = current === 'dark' ? 'light' : 'dark';
    applyTheme(next);
  }

  ['theme', 'theme-toggle', 'compare-theme'].forEach(id => {
    const btn = $(id);
    if (btn) btn.addEventListener('click', toggleTheme);
  });

  try {
    const saved = localStorage.getItem('evo-theme');
    if (saved === 'light' || saved === 'dark') applyTheme(saved);
  } catch (_) { }

  /* ── 4. Unified Remediation & Evolution Workflow ──────────────────────── */
  function wireRemediationWorkflow() {
    const policyPanel = $('panel-policy');
    const emptyState = $('remediation-empty-state');
    const activeWorkflow = $('remediation-active-workflow');

    const stageEvidence = $('stage-evidence');
    const stageDiagnosis = $('stage-diagnosis');
    const stageProposal = $('stage-proposal');
    const stageVerification = $('stage-verification');

    const findingTitle = $('remedy-finding-title');
    const findingBadge = $('remedy-finding-badge');
    const evidenceBody = $('remedy-evidence-body');
    const diagnosisBody = $('remedy-diagnosis-body');
    const proposalBody = $('remedy-proposal-body');
    const verificationBody = $('remedy-verification-body');

    const filterInput = $('find-filter');

    // Filter findings in master list
    if (filterInput) {
      filterInput.addEventListener('input', () => {
        const query = filterInput.value.trim().toLowerCase();
        qsa('.entity-row', policyPanel).forEach(row => {
          const text = row.textContent.toLowerCase();
          row.style.display = query.length > 0 && !text.includes(query) ? 'none' : '';
        });
      });
    }

    // Monitor policyPanel for rendered findings count
    const countBadge = $('nav-findings-count');
    const policyObserver = new MutationObserver(() => {
      const rows = qsa('.entity-row', policyPanel);
      if (countBadge) {
        countBadge.textContent = String(rows.length);
        countBadge.hidden = rows.length === 0;
      }
    });
    if (policyPanel) {
      policyObserver.observe(policyPanel, { childList: true, subtree: true });
    }

    // Action button handlers in Remediation detail
    $('remedy-diagnose-btn')?.addEventListener('click', () => {
      const diagBtn = qsa('button', $('inspector-body') || document)
        .find(b => b.textContent.includes('Diagnose finding') || b.textContent.includes('Diagnose'));
      if (diagBtn) diagBtn.click();
    });

    $('remedy-propose-btn')?.addEventListener('click', () => {
      const propBtn = qsa('button', $('inspector-body') || document)
        .find(b => b.textContent.includes('Review repair proposal') || b.textContent.includes('repair proposal'));
      if (propBtn) propBtn.click();
    });

    $('remedy-download-btn')?.addEventListener('click', () => {
      const dlBtn = qsa('button', $('inspector-body') || document)
        .find(b => b.textContent.includes('Download patch'));
      if (dlBtn) dlBtn.click();
    });

    $('remedy-verify-btn')?.addEventListener('click', () => {
      const verBtn = qsa('button', $('inspector-body') || document)
        .find(b => b.textContent.includes('View verification') || b.textContent.includes('verification'));
      if (verBtn) verBtn.click();
    });

    $('remedy-graph-btn')?.addEventListener('click', () => {
      const graphBtn = qsa('button', $('inspector-body') || document)
        .find(b => b.textContent.includes('Show finding graph') || b.textContent.includes('graph'));
      if (graphBtn) {
        graphBtn.click();
        go('architecture');
      }
    });

    // Observe inspector-body to sync the deep remediation workflow in real-time
    const inspectorBody = $('inspector-body');
    const inspectorTitle = $('inspector-title');
    const inspectorKind = $('inspector-kind');

    function syncInspectorToRemediation() {
      if (!inspectorBody || !activeWorkflow) return;

      const title = inspectorTitle?.textContent?.trim() || '';
      const kind = inspectorKind?.textContent?.trim() || '';

      // Check what stage the inspector is in
      const isFinding = kind.includes('POLICY') || kind.includes('FINDING');
      const isDiagnosis = kind.includes('DIAGNOSIS');
      const isProposal = kind.includes('PROPOSED') || kind.includes('CHANGE');
      const isVerification = kind.includes('VERIF') || title.includes('PR preview');

      if (isFinding || isDiagnosis || isProposal || isVerification) {
        if (emptyState) emptyState.hidden = true;
        activeWorkflow.hidden = false;
      }

      if (isFinding) {
        if (findingTitle) findingTitle.textContent = title;
        if (findingBadge) findingBadge.textContent = 'EVALUATED';
        if (stageEvidence) stageEvidence.hidden = false;
        if (evidenceBody) {
          evidenceBody.innerHTML = '';
          for (const node of inspectorBody.childNodes) {
            evidenceBody.appendChild(node.cloneNode(true));
          }
        }
      } else if (isDiagnosis) {
        if (stageDiagnosis) stageDiagnosis.hidden = false;
        if (diagnosisBody) {
          diagnosisBody.innerHTML = '';
          for (const node of inspectorBody.childNodes) {
            diagnosisBody.appendChild(node.cloneNode(true));
          }
        }
        const hasProposalBtn = qsa('button', inspectorBody)
          .some(b => b.textContent.includes('Review repair proposal') || b.textContent.includes('repair proposal'));
        const diagMode = $('remedy-diagnosis-mode');
        const proposeBtn = $('remedy-propose-btn');
        if (hasProposalBtn) {
          if (diagMode) {
            diagMode.textContent = 'Citations Validated';
            diagMode.className = 'badge green';
          }
          if (proposeBtn) proposeBtn.hidden = false;
        } else {
          if (diagMode) {
            diagMode.textContent = 'Diagnosis Withheld';
            diagMode.className = 'badge';
          }
          if (proposeBtn) proposeBtn.hidden = true;
        }
      } else if (isProposal) {
        if (stageProposal) stageProposal.hidden = false;
        if (proposalBody) {
          proposalBody.innerHTML = '';
          for (const node of inspectorBody.childNodes) {
            proposalBody.appendChild(node.cloneNode(true));
          }
        }
      } else if (isVerification) {
        if (stageVerification) stageVerification.hidden = false;
        if (verificationBody) {
          verificationBody.innerHTML = '';
          for (const node of inspectorBody.childNodes) {
            verificationBody.appendChild(node.cloneNode(true));
          }
        }
      }
    }

    if (inspectorBody) {
      const inspObserver = new MutationObserver(syncInspectorToRemediation);
      inspObserver.observe(inspectorBody, { childList: true, subtree: true });
    }
  }

  /* ── 5. Keyboard Navigation ───────────────────────────────────────────── */
  document.addEventListener('keydown', e => {
    const tag = document.activeElement?.tagName;
    const isInput = ['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) || document.activeElement?.isContentEditable;

    if (e.key === '/' && !isInput && !e.ctrlKey && !e.metaKey) {
      e.preventDefault();
      const filterInput = $('find-filter') || $('search');
      filterInput?.focus();
      return;
    }

    if (!isInput && !e.ctrlKey && !e.metaKey && !e.altKey) {
      const keyMap = {
        '1': 'overview',
        '2': 'architecture',
        '3': 'remediation',
        '4': 'evidence'
      };
      if (keyMap[e.key]) {
        e.preventDefault();
        go(keyMap[e.key]);
      }
    }
  });

  /* ── 6. Initial Route Discovery ───────────────────────────────────────── */
  function initRouting() {
    const hash = (location.hash || '').replace(/^#/, '').trim();
    const routeAliases = {
      findings: 'remediation',
      repair: 'remediation',
      verification: 'remediation',
      evolution: 'remediation'
    };
    const resolved = routeAliases[hash] || hash;
    if (resolved && $('view-' + resolved)) {
      go(resolved);
    } else {
      go('overview');
    }
  }

  /* ── 7. Bootstrap on DOM Ready ────────────────────────────────────────── */
  function init() {
    wireSidebarResizer();
    wireRepositorySync();
    wireRemediationWorkflow();
    initRouting();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
