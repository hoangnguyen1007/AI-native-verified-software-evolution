/**
 * Evo UX Enhancement Layer
 * Layered on top of app.js — zero modifications to app.js.
 * Adds: tab streamlining, keyboard polish, canvas overlay wiring,
 *       smooth page transitions, smart focus management, status UX.
 */
(() => {
  'use strict';

  /* ── Helpers ─────────────────────────────────────────────────────────── */
  const $ = id => document.getElementById(id);
  const qs = (sel, ctx = document) => ctx.querySelector(sel);
  const qsa = (sel, ctx = document) => [...ctx.querySelectorAll(sel)];

  /* ── 1. FOCUS RING: suppress mouse, restore keyboard ─────────────────── */
  document.addEventListener('mousedown', () => document.documentElement.setAttribute('data-using-mouse', ''));
  document.addEventListener('keydown', e => {
    if (['Tab', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key))
      document.documentElement.removeAttribute('data-using-mouse');
  });
  const focusStyle = document.createElement('style');
  focusStyle.textContent = '[data-using-mouse] :focus { outline: none !important; }';
  document.head.appendChild(focusStyle);

  /* ── 2. TAB STREAMLINING ─────────────────────────────────────────────── */
  // Core tabs always visible. Secondary tabs hidden behind a "More ▾" dropdown.
  const SECONDARY_TABS = ['tab-spring', 'tab-configurations', 'tab-impact', 'tab-structure'];
  const PRIORITY_ORDER = ['tab-overview', 'tab-graph', 'tab-policy', 'tab-metrics', 'tab-gaps'];

  function streamlineTabs() {
    const tabsEl = $('tabs');
    if (!tabsEl) return;

    // Already streamlined
    if (tabsEl.dataset.streamlined) return;
    tabsEl.dataset.streamlined = '1';

    // Hide secondary tabs
    SECONDARY_TABS.forEach(id => {
      const btn = $(id);
      if (btn) btn.style.display = 'none';
    });

    // Build a "More" dropdown containing the hidden tabs
    const moreWrapper = document.createElement('div');
    moreWrapper.className = 'tab-more-wrapper';
    moreWrapper.style.cssText = 'position:relative;display:inline-flex;align-items:center;';

    const moreBtn = document.createElement('button');
    moreBtn.className = 'tab-more-btn';
    moreBtn.setAttribute('aria-haspopup', 'listbox');
    moreBtn.setAttribute('aria-expanded', 'false');
    moreBtn.textContent = 'More';
    moreBtn.style.cssText = 'border:0;background:transparent;color:var(--text-3);padding:0 14px;height:40px;font-size:13px;font-weight:500;cursor:pointer;transition:color 0.15s ease;display:inline-flex;align-items:center;gap:5px;';

    const caret = document.createElement('span');
    caret.style.cssText = 'font-size:9px;opacity:0.6;';
    caret.textContent = '▾';
    moreBtn.appendChild(caret);

    const dropdown = document.createElement('div');
    dropdown.className = 'tab-more-dropdown';
    dropdown.setAttribute('role', 'listbox');
    dropdown.style.cssText = [
      'position:absolute;top:calc(100% + 6px);right:0;z-index:30;',
      'background:var(--surface);border:1px solid var(--line-mid);',
      'border-radius:9px;box-shadow:var(--shadow);padding:5px;',
      'min-width:170px;display:none;'
    ].join('');

    SECONDARY_TABS.forEach(id => {
      const sourceBtn = $(id);
      if (!sourceBtn) return;
      const item = document.createElement('button');
      item.type = 'button';
      item.textContent = sourceBtn.textContent;
      item.dataset.tabTarget = id;
      item.style.cssText = [
        'display:block;width:100%;padding:8px 12px;text-align:left;',
        'background:none;border:0;color:var(--text-2);border-radius:6px;',
        'font-size:12.5px;min-height:34px;cursor:pointer;transition:background 0.12s ease;'
      ].join('');
      item.addEventListener('mouseenter', () => item.style.background = 'var(--surface-2)');
      item.addEventListener('mouseleave', () => item.style.background = '');
      item.addEventListener('click', () => {
        // Click the real underlying tab so app.js handles it
        sourceBtn.click();
        closeMore();
        // Highlight the more button when a secondary tab is active
        highlightMoreBtn();
      });
      dropdown.appendChild(item);
    });

    function openMore() {
      dropdown.style.display = 'block';
      moreBtn.setAttribute('aria-expanded', 'true');
      moreBtn.style.color = 'var(--text)';
    }
    function closeMore() {
      dropdown.style.display = 'none';
      moreBtn.setAttribute('aria-expanded', 'false');
    }
    function highlightMoreBtn() {
      const anySecondaryActive = SECONDARY_TABS.some(id => $(id)?.getAttribute('aria-selected') === 'true');
      moreBtn.style.color = anySecondaryActive ? 'var(--text)' : '';
      moreBtn.style.boxShadow = anySecondaryActive ? 'inset 0 -2px 0 var(--accent)' : '';
    }

    moreBtn.addEventListener('click', e => {
      e.stopPropagation();
      dropdown.style.display === 'none' ? openMore() : closeMore();
    });

    document.addEventListener('click', e => {
      if (!moreWrapper.contains(e.target)) closeMore();
    });
    document.addEventListener('keydown', e => { if (e.key === 'Escape') closeMore(); });

    moreWrapper.appendChild(moreBtn);
    moreWrapper.appendChild(dropdown);
    tabsEl.appendChild(moreWrapper);

    // Watch for tab selection changes to update "more" highlight
    const tabObserver = new MutationObserver(highlightMoreBtn);
    qsa('[role=tab]', tabsEl).forEach(tab => {
      tabObserver.observe(tab, { attributes: true, attributeFilter: ['aria-selected'] });
    });
  }

  /* ── 3. CANVAS OVERLAY WIRING ─────────────────────────────────────────── */
  // The canvas overlay is a structural placeholder in index.html.
  // We wire it up: open it when the graph tab is activated (optional),
  // and provide a close button. The graph remains in its original panel
  // but we provide a "Expand to canvas" affordance.

  function wireCanvasOverlay() {
    const overlay = $('canvas-overlay');
    const closeBtn = $('canvas-close');
    const graphPanel = $('panel-graph');
    if (!overlay || !closeBtn || !graphPanel) return;

    // Remove hidden attribute so CSS controls display via transform
    overlay.removeAttribute('hidden');
    overlay.style.display = '';

    function openCanvas() {
      overlay.classList.add('is-open');
      overlay.setAttribute('aria-hidden', 'false');
      closeBtn.focus();
    }
    function closeCanvas() {
      overlay.classList.remove('is-open');
      overlay.setAttribute('aria-hidden', 'true');
    }

    closeBtn.addEventListener('click', closeCanvas);
    document.addEventListener('keydown', e => {
      if (e.key === 'Escape' && overlay.classList.contains('is-open')) closeCanvas();
    });

    // Add "Open in canvas" expand button to the graph panel after app.js populates it
    function addExpandButton() {
      if (qs('.graph-expand-btn', graphPanel)) return; // already added
      const graphCanvas = qs('.graph-canvas', graphPanel);
      if (!graphCanvas) return;

      const expandBtn = document.createElement('button');
      expandBtn.className = 'button subtle graph-expand-btn';
      expandBtn.style.cssText = 'position:absolute;top:10px;right:10px;z-index:5;font-size:11.5px;height:30px;padding:0 10px;';
      expandBtn.textContent = '⤢ Expand';
      expandBtn.title = 'Open graph in full canvas panel';
      expandBtn.addEventListener('click', openCanvas);

      // Position the canvas relative to the graph canvas container
      if (getComputedStyle(graphCanvas).position === 'static') graphCanvas.style.position = 'relative';
      graphCanvas.appendChild(expandBtn);
    }

    // Watch for graph panel content changes
    const graphObserver = new MutationObserver(addExpandButton);
    graphObserver.observe(graphPanel, { childList: true, subtree: true });
  }

  /* ── 4. SMOOTH TAB CONTENT TRANSITIONS ───────────────────────────────── */
  function wireTabTransitions() {
    const tabsEl = $('tabs');
    if (!tabsEl) return;

    const style = document.createElement('style');
    style.textContent = `
      [role=tabpanel] { animation: none; }
      [role=tabpanel].tab-entering {
        animation: tabFadeIn 0.18s ease forwards;
      }
      @keyframes tabFadeIn {
        from { opacity: 0; transform: translateY(4px); }
        to   { opacity: 1; transform: translateY(0); }
      }
    `;
    document.head.appendChild(style);

    // Observe tabpanel hidden attribute changes
    qsa('[role=tabpanel]').forEach(panel => {
      const panelObserver = new MutationObserver(mutations => {
        mutations.forEach(m => {
          if (m.attributeName === 'hidden' && !panel.hidden) {
            panel.classList.remove('tab-entering');
            // Trigger reflow
            void panel.offsetWidth;
            panel.classList.add('tab-entering');
          }
        });
      });
      panelObserver.observe(panel, { attributes: true, attributeFilter: ['hidden'] });
    });
  }

  /* ── 5. ANALYSIS JOB STATUS TOAST / PULSE ────────────────────────────── */
  // Add a subtle pulse to the connection dot when a job is running
  function wireJobPulse() {
    const dot = $('connection-dot');
    if (!dot) return;

    const pulseStyle = document.createElement('style');
    pulseStyle.textContent = `
      .connection-dot.pulsing {
        animation: dotPulse 1.6s ease-in-out infinite;
      }
      @keyframes dotPulse {
        0%, 100% { opacity: 1; transform: scale(1); }
        50% { opacity: 0.5; transform: scale(1.4); }
      }
    `;
    document.head.appendChild(pulseStyle);

    // Observe job state badge to detect running state
    const jobState = $('job-state');
    if (!jobState) return;

    const stateObserver = new MutationObserver(() => {
      const text = jobState.textContent.trim().toUpperCase();
      const running = ['RUNNING', 'QUEUED', 'CANCELLING', 'ACQUISITION', 'PLATFORM', 'ANALYSIS', 'GRAPH', 'POLICY', 'PUBLICATION'].includes(text);
      dot.classList.toggle('pulsing', running);
    });
    stateObserver.observe(jobState, { childList: true, characterData: true, subtree: true });
  }

  /* ── 6. SMART BADGE LABELS (remap technical to human language) ────────── */
  const BADGE_LABELS = {
    'SUCCEEDED': 'Completed',
    'PARTIAL': 'Completed · warnings',
    'TIMED_OUT': 'Timed out',
    'CANCELLING': 'Cancelling…',
    'ACQUISITION': 'Cloning…',
    'PLATFORM': 'Reading build…',
    'ANALYSIS': 'Analysing…',
    'GRAPH': 'Building graph…',
    'POLICY': 'Checking rules…',
    'PUBLICATION': 'Finishing…',
    'REUSED': 'Reused',
  };

  function wireSmartBadges() {
    const jobState = $('job-state');
    if (!jobState) return;

    const badgeObserver = new MutationObserver(() => {
      const raw = jobState.dataset.rawState || jobState.textContent.trim();
      if (!jobState.dataset.rawState) jobState.dataset.rawState = raw;
      const human = BADGE_LABELS[raw.toUpperCase()];
      if (human && jobState.textContent !== human) jobState.textContent = human;
    });
    badgeObserver.observe(jobState, { childList: true, characterData: true, subtree: true });
  }

  /* ── 7. KEYBOARD SHORTCUT: G → Go to graph tab ────────────────────────── */
  function wireExtraShortcuts() {
    document.addEventListener('keydown', e => {
      const tag = document.activeElement?.tagName;
      const inInput = ['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) || document.activeElement?.isContentEditable;
      if (inInput || e.ctrlKey || e.metaKey || e.altKey) return;
      const ws = $('analysis-workspace');
      if (!ws || ws.hidden) return;

      if (e.key === 'g') {
        e.preventDefault();
        $('tab-graph')?.click();
      }
      if (e.key === 'o') {
        e.preventDefault();
        $('tab-overview')?.click();
      }
      if (e.key === 'r') {
        e.preventDefault();
        $('tab-policy')?.click();
      }
    });
  }

  /* ── 8. TOPBAR BREADCRUMB: show repo name when selected ──────────────── */
  function wireBreadcrumb() {
    const title = $('repository-title');
    if (!title) return;

    // Inject a subtle breadcrumb span in the topbar
    const topbar = qs('.topbar');
    if (!topbar) return;

    const crumb = document.createElement('span');
    crumb.id = 'topbar-crumb';
    crumb.style.cssText = [
      'font-size:12px;color:var(--text-3);font-family:var(--font-mono);',
      'max-width:260px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;'
    ].join('');
    // Insert after topbar-nav
    const nav = qs('.topbar-nav', topbar);
    if (nav && nav.nextSibling) topbar.insertBefore(crumb, nav.nextSibling);

    const titleObserver = new MutationObserver(() => {
      const text = title.textContent.trim();
      if (text && text !== 'Explore your architecture') {
        crumb.textContent = text;
        crumb.style.display = '';
      } else {
        crumb.style.display = 'none';
      }
    });
    titleObserver.observe(title, { childList: true, characterData: true, subtree: true });
  }

  /* ── 9. PAGE LOADED INDICATOR / PROGRESS BAR ─────────────────────────── */
  function wireProgressBar() {
    const bar = document.createElement('div');
    bar.id = 'ux-progress';
    bar.style.cssText = [
      'position:fixed;top:0;left:0;height:2px;width:0;z-index:1000;',
      'background:var(--accent);transition:width 0.3s ease,opacity 0.4s ease;',
      'pointer-events:none;opacity:0;'
    ].join('');
    document.body.appendChild(bar);

    let timer = null;
    function start() {
      clearTimeout(timer);
      bar.style.opacity = '1';
      bar.style.width = '0';
      void bar.offsetWidth;
      bar.style.width = '70%';
    }
    function finish() {
      bar.style.width = '100%';
      timer = setTimeout(() => { bar.style.opacity = '0'; bar.style.width = '0'; }, 400);
    }

    // Observe job-panel visibility to trigger progress
    const jobPanel = $('job-panel');
    if (!jobPanel) return;
    let wasHidden = jobPanel.hidden;

    const panelObserver = new MutationObserver(() => {
      const nowHidden = jobPanel.hidden;
      if (wasHidden && !nowHidden) start();
      if (!wasHidden && nowHidden) finish();
      wasHidden = nowHidden;
    });
    panelObserver.observe(jobPanel, { attributes: true, attributeFilter: ['hidden'] });

    // Also finish when analysis-workspace becomes visible
    const ws = $('analysis-workspace');
    if (ws) {
      const wsObserver = new MutationObserver(() => { if (!ws.hidden) finish(); });
      wsObserver.observe(ws, { attributes: true, attributeFilter: ['hidden'] });
    }
  }

  /* ── 10. SIDEBAR ITEM RIPPLE (subtle interaction feedback) ───────────── */
  function wireSidebarRipple() {
    const style = document.createElement('style');
    style.textContent = `
      .sidebar-item { overflow: hidden; }
      .sidebar-ripple {
        position: absolute; border-radius: 50%;
        background: rgba(16,185,129,0.12);
        transform: scale(0); animation: ripple 0.4s ease-out forwards;
        pointer-events: none;
      }
      @keyframes ripple {
        to { transform: scale(4); opacity: 0; }
      }
    `;
    document.head.appendChild(style);

    document.addEventListener('click', e => {
      const item = e.target.closest('.sidebar-item');
      if (!item) return;
      if (getComputedStyle(item).position === 'static') item.style.position = 'relative';
      const rect = item.getBoundingClientRect();
      const size = Math.max(rect.width, rect.height);
      const ripple = document.createElement('span');
      ripple.className = 'sidebar-ripple';
      ripple.style.cssText = `width:${size}px;height:${size}px;left:${e.clientX - rect.left - size/2}px;top:${e.clientY - rect.top - size/2}px;`;
      item.appendChild(ripple);
      ripple.addEventListener('animationend', () => ripple.remove());
    });
  }

  /* ── 11. NOTICE AUTO-DISMISS ─────────────────────────────────────────── */
  function wireNoticeDismiss() {
    const notice = $('notice');
    if (!notice) return;
    let dismissTimer = null;

    const noticeObserver = new MutationObserver(() => {
      clearTimeout(dismissTimer);
      if (!notice.hidden && !notice.classList.contains('error')) {
        // Auto-dismiss non-error notices after 6s
        dismissTimer = setTimeout(() => {
          notice.style.transition = 'opacity 0.5s ease';
          notice.style.opacity = '0';
          setTimeout(() => { notice.hidden = true; notice.style.opacity = ''; }, 500);
        }, 6000);
      }
    });
    noticeObserver.observe(notice, { attributes: true, attributeFilter: ['hidden'], childList: true });
  }

  /* ── Init: defer until DOM ready ──────────────────────────────────────── */
  function init() {
    streamlineTabs();
    wireCanvasOverlay();
    wireTabTransitions();
    wireJobPulse();
    wireSmartBadges();
    wireExtraShortcuts();
    wireBreadcrumb();
    wireProgressBar();
    wireSidebarRipple();
    wireNoticeDismiss();

    // Re-streamline if tabs appear late (app.js may render tabs lazily)
    const analysisWs = $('analysis-workspace');
    if (analysisWs) {
      const wsObserver = new MutationObserver(() => {
        if (!analysisWs.hidden) streamlineTabs();
      });
      wsObserver.observe(analysisWs, { attributes: true, attributeFilter: ['hidden'] });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();

