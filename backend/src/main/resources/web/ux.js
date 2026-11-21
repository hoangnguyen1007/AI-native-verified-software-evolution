/**
 * Evo UX Enhancement Layer — Ultra Pro Edition
 * Layered on top of app.js — zero modifications to app.js.
 *
 * Sections:
 *  1. Focus ring
 *  2. Sidebar toggle
 *  3. Tab streamlining (pill nav, More dropdown)
 *  4. Canvas overlay wiring
 *  5. Smooth tab transitions
 *  6. Job status pulse
 *  7. Smart badge labels
 *  8. Keyboard shortcuts
 *  9. Topbar breadcrumb
 * 10. Progress bar
 * 11. Sidebar ripple
 * 12. Notice → Toast redirect
 * 13. Repair patch syntax highlight
 * 14. Tab scroll reset
 * 15. Toast system
 * 16. Skeleton loaders
 * 17. Graph fullscreen
 * 18. Shortcut overlay (?)
 * 19. Stat number counter animation
 * 20. Context selector shortcut (C)
 * 21. Graph expand button enhancement
 */
(() => {
  'use strict';

  const $ = id => document.getElementById(id);
  const qs = (sel, ctx = document) => ctx.querySelector(sel);
  const qsa = (sel, ctx = document) => [...ctx.querySelectorAll(sel)];

  /* ── 1. FOCUS RING: suppress mouse, restore keyboard ─────────────────── */
  document.addEventListener('mousedown', () =>
    document.documentElement.setAttribute('data-using-mouse', ''));
  document.addEventListener('keydown', e => {
    if (['Tab', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key))
      document.documentElement.removeAttribute('data-using-mouse');
  });
  const focusStyle = document.createElement('style');
  focusStyle.textContent = '[data-using-mouse] :focus { outline: none !important; }';
  document.head.appendChild(focusStyle);

  /* ── 2. SIDEBAR TOGGLE ───────────────────────────────────────────────── */
  function wireSidebarToggle() {
    const btn = $('sidebar-toggle');
    const shell = qs('.app-shell');
    if (!btn || !shell) return;

    const saved = localStorage.getItem('evo-sidebar-collapsed');
    if (saved === '1') {
      shell.classList.add('sidebar-collapsed');
      btn.setAttribute('aria-expanded', 'false');
    }

    btn.addEventListener('click', () => {
      const collapsed = shell.classList.toggle('sidebar-collapsed');
      btn.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
      localStorage.setItem('evo-sidebar-collapsed', collapsed ? '1' : '0');
    });

    document.addEventListener('click', e => {
      if (window.innerWidth > 760) return;
      if (!shell.classList.contains('mobile-sidebar-open')) return;
      const sidebar = $('sidebar');
      if (sidebar && !sidebar.contains(e.target) && !btn.contains(e.target)) {
        shell.classList.remove('mobile-sidebar-open');
        btn.setAttribute('aria-expanded', 'false');
      }
    });
  }

  /* ── 3. TAB STREAMLINING — pill nav + More dropdown ──────────────────── */
  const SECONDARY_TABS = ['tab-spring', 'tab-configurations', 'tab-impact'];
  const ALWAYS_VISIBLE  = ['tab-overview', 'tab-structure', 'tab-graph', 'tab-policy', 'tab-metrics'];

  function streamlineTabs() {
    const tabsEl = $('tabs');
    if (!tabsEl || tabsEl.dataset.streamlined) return;
    tabsEl.dataset.streamlined = '1';

    SECONDARY_TABS.forEach(id => {
      const btn = $(id);
      if (btn) btn.style.display = 'none';
    });

    const moreWrapper = document.createElement('div');
    moreWrapper.className = 'tab-more-wrapper';
    moreWrapper.style.cssText = 'position:relative;display:inline-flex;align-items:center;';

    const moreBtn = document.createElement('button');
    moreBtn.className = 'tab-more-btn';
    moreBtn.setAttribute('aria-haspopup', 'listbox');
    moreBtn.setAttribute('aria-expanded', 'false');
    moreBtn.innerHTML = '⋯ More';

    const dropdown = document.createElement('div');
    dropdown.className = 'tab-more-dropdown';
    dropdown.setAttribute('role', 'listbox');
    dropdown.style.display = 'none';

    SECONDARY_TABS.forEach(id => {
      const sourceBtn = $(id);
      if (!sourceBtn) return;
      const item = document.createElement('button');
      item.type = 'button';
      item.textContent = sourceBtn.textContent;
      item.dataset.tabTarget = id;
      item.style.cssText = [
        'display:block;width:100%;padding:8px 12px;text-align:left;',
        'background:none;border:0;color:var(--text-2);border-radius:var(--radius);',
        'font-size:12px;min-height:32px;cursor:pointer;transition:background 0.1s,color 0.1s;'
      ].join('');
      item.addEventListener('mouseenter', () => { item.style.background = 'var(--surface-3)'; item.style.color = 'var(--text)'; });
      item.addEventListener('mouseleave', () => { item.style.background = ''; item.style.color = ''; });
      item.addEventListener('click', () => { sourceBtn.click(); closeMore(); highlightMoreBtn(); });
      dropdown.appendChild(item);
    });

    function openMore() {
      dropdown.style.display = 'block';
      moreBtn.setAttribute('aria-expanded', 'true');
    }
    function closeMore() {
      dropdown.style.display = 'none';
      moreBtn.setAttribute('aria-expanded', 'false');
    }
    function highlightMoreBtn() {
      const anyActive = SECONDARY_TABS.some(id => $(id)?.getAttribute('aria-selected') === 'true');
      moreBtn.style.color = anyActive ? 'var(--accent)' : '';
      moreBtn.style.background = anyActive ? 'var(--accent-s)' : '';
      moreBtn.style.borderColor = anyActive ? 'rgba(34,197,94,0.25)' : '';
    }

    moreBtn.addEventListener('click', e => {
      e.stopPropagation();
      dropdown.style.display === 'none' ? openMore() : closeMore();
    });
    document.addEventListener('click', e => { if (!moreWrapper.contains(e.target)) closeMore(); });
    document.addEventListener('keydown', e => { if (e.key === 'Escape') closeMore(); });

    moreWrapper.appendChild(moreBtn);
    moreWrapper.appendChild(dropdown);
    tabsEl.appendChild(moreWrapper);

    const tabObserver = new MutationObserver(highlightMoreBtn);
    qsa('[role=tab]', tabsEl).forEach(tab =>
      tabObserver.observe(tab, { attributes: true, attributeFilter: ['aria-selected'] }));
  }

  /* ── 4. CANVAS OVERLAY ───────────────────────────────────────────────── */
  function wireCanvasOverlay() {
    const overlay  = $('canvas-overlay');
    const closeBtn = $('canvas-close');
    if (!overlay || !closeBtn) return;

    overlay.removeAttribute('hidden');

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

    const headerKind = $('canvas-kind');
    if (headerKind) {
      const bodyObserver = new MutationObserver(() => {
        const kind = headerKind.textContent.trim();
        const isAI = /DIAGNOSIS|REPAIR|PROPOSAL|VERIFICATION/i.test(kind);
        let aiSignal = overlay.querySelector('.ai-signal');
        if (isAI && !aiSignal) {
          aiSignal = document.createElement('span');
          aiSignal.className = 'ai-signal';
          aiSignal.textContent = 'AI';
          headerKind.parentNode.insertBefore(aiSignal, headerKind.nextSibling);
        } else if (!isAI && aiSignal) {
          aiSignal.remove();
        }
      });
      bodyObserver.observe(headerKind, { childList: true, characterData: true, subtree: true });
    }

    const graphPanel = $('panel-graph');
    if (graphPanel) {
      function addExpandButton() {
        if (qs('.graph-expand-btn', graphPanel)) return;
        const graphCanvas = qs('.graph-canvas', graphPanel);
        if (!graphCanvas) return;
        const expandBtn = document.createElement('button');
        expandBtn.className = 'button subtle graph-expand-btn';
        expandBtn.textContent = '⤢ Fullscreen';
        expandBtn.title = 'Open graph fullscreen (F)';
        expandBtn.addEventListener('click', () => openGraphFullscreen(graphCanvas));
        if (getComputedStyle(graphCanvas).position === 'static')
          graphCanvas.style.position = 'relative';
        graphCanvas.appendChild(expandBtn);
      }
      new MutationObserver(addExpandButton).observe(graphPanel, { childList: true, subtree: true });
    }

    window._evoOpenCanvas = openCanvas;
  }

  /* ── 5. SMOOTH TAB TRANSITIONS ───────────────────────────────────────── */
  function wireTabTransitions() {
    qsa('[role=tabpanel]').forEach(panel => {
      new MutationObserver(mutations => {
        mutations.forEach(m => {
          if (m.attributeName === 'hidden' && !panel.hidden) {
            panel.classList.remove('tab-entering');
            void panel.offsetWidth;
            panel.classList.add('tab-entering');
          }
        });
      }).observe(panel, { attributes: true, attributeFilter: ['hidden'] });
    });
  }

  /* ── 6. JOB STATUS PULSE ─────────────────────────────────────────────── */
  function wireJobPulse() {
    const dot = $('connection-dot');
    const jobState = $('job-state');
    if (!dot || !jobState) return;
    new MutationObserver(() => {
      const text = jobState.textContent.trim().toUpperCase();
      const running = /RUNNING|QUEUED|CANCELLING|ACQUISITION|PLATFORM|ANALYSIS|GRAPH|POLICY|PUBLICATION/.test(text);
      dot.classList.toggle('pulsing', running);
    }).observe(jobState, { childList: true, characterData: true, subtree: true });
  }

  /* ── 7. SMART BADGE LABELS ───────────────────────────────────────────── */
  const BADGE_LABELS = {
    'SUCCEEDED':   'Complete',
    'PARTIAL':     'Partial',
    'TIMED_OUT':   'Timed out',
    'CANCELLING':  'Cancelling…',
    'ACQUISITION': 'Cloning…',
    'PLATFORM':    'Build scan…',
    'ANALYSIS':    'Analysing…',
    'GRAPH':       'Graph build…',
    'POLICY':      'Rules check…',
    'PUBLICATION': 'Finishing…',
  };
  function wireSmartBadges() {
    const jobState = $('job-state');
    if (!jobState) return;
    new MutationObserver(() => {
      const raw = jobState.dataset.rawState || jobState.textContent.trim();
      if (!jobState.dataset.rawState) jobState.dataset.rawState = raw;
      const human = BADGE_LABELS[raw.toUpperCase()];
      if (human && jobState.textContent !== human) jobState.textContent = human;
    }).observe(jobState, { childList: true, characterData: true, subtree: true });
  }

  /* ── 8. KEYBOARD SHORTCUTS ───────────────────────────────────────────── */
  function wireExtraShortcuts() {
    document.addEventListener('keydown', e => {
      const tag = document.activeElement?.tagName;
      const inInput = ['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) ||
                      document.activeElement?.isContentEditable;

      if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
        e.preventDefault();
        const searchEl = $('search');
        if (searchEl) { searchEl.focus(); searchEl.select(); }
        return;
      }

      if (inInput || e.ctrlKey || e.metaKey || e.altKey) return;
      const ws = $('analysis-workspace');

      // ? → shortcut overlay
      if (e.key === '?') {
        e.preventDefault();
        toggleShortcutOverlay();
        return;
      }

      // F → graph fullscreen
      if (e.key === 'f' || e.key === 'F') {
        const graphPanel = $('panel-graph');
        if (graphPanel && !graphPanel.hidden) {
          e.preventDefault();
          const canvas = qs('.graph-canvas', graphPanel);
          if (canvas) openGraphFullscreen(canvas);
        }
        return;
      }

      // C → focus context selector
      if (e.key === 'c' || e.key === 'C') {
        const ctx = $('context');
        if (ctx && ws && !ws.hidden) { e.preventDefault(); ctx.focus(); }
        return;
      }

      if (!ws || ws.hidden) return;

      if (e.key === '/') {
        e.preventDefault();
        const searchEl = $('search');
        if (searchEl) { searchEl.focus(); searchEl.select(); }
        return;
      }

      const map = { g: 'tab-graph', o: 'tab-overview', r: 'tab-policy', m: 'tab-metrics', s: 'tab-structure' };
      if (map[e.key]) { e.preventDefault(); $(map[e.key])?.click(); }
    });
  }

  /* ── 9. TOPBAR BREADCRUMB ────────────────────────────────────────────── */
  function wireBreadcrumb() {
    const title = $('repository-title');
    const topbar = qs('.topbar');
    if (!title || !topbar) return;
    const crumb = document.createElement('span');
    crumb.id = 'topbar-crumb';
    crumb.style.display = 'none';
    const nav = qs('.topbar-nav', topbar);
    if (nav?.nextSibling) topbar.insertBefore(crumb, nav.nextSibling);
    new MutationObserver(() => {
      const text = title.textContent.trim();
      const show = text && text !== 'Explore your architecture';
      crumb.textContent = show ? text : '';
      crumb.style.display = show ? '' : 'none';
    }).observe(title, { childList: true, characterData: true, subtree: true });
  }

  /* ── 10. PROGRESS BAR ────────────────────────────────────────────────── */
  function wireProgressBar() {
    const bar = document.createElement('div');
    bar.id = 'ux-progress';
    bar.style.cssText = [
      'position:fixed;top:0;left:0;height:2px;width:0;z-index:1000;',
      'background:linear-gradient(90deg,var(--accent),var(--cyan));',
      'transition:width 0.35s ease,opacity 0.45s ease;pointer-events:none;opacity:0;'
    ].join('');
    document.body.appendChild(bar);
    let timer;
    const start = () => {
      clearTimeout(timer);
      bar.style.opacity = '1'; bar.style.width = '0';
      void bar.offsetWidth; bar.style.width = '70%';
    };
    const finish = () => {
      bar.style.width = '100%';
      timer = setTimeout(() => { bar.style.opacity = '0'; bar.style.width = '0'; }, 420);
    };
    const jobPanel = $('job-panel');
    if (jobPanel) {
      let wasHidden = jobPanel.hidden;
      new MutationObserver(() => {
        const now = jobPanel.hidden;
        if (wasHidden && !now) start();
        if (!wasHidden && now) finish();
        wasHidden = now;
      }).observe(jobPanel, { attributes: true, attributeFilter: ['hidden'] });
    }
    const ws = $('analysis-workspace');
    if (ws) {
      new MutationObserver(() => { if (!ws.hidden) finish(); })
        .observe(ws, { attributes: true, attributeFilter: ['hidden'] });
    }
  }

  /* ── 11. SIDEBAR RIPPLE ──────────────────────────────────────────────── */
  function wireSidebarRipple() {
    document.addEventListener('click', e => {
      const item = e.target.closest('.sidebar-item');
      if (!item) return;
      if (getComputedStyle(item).position === 'static') item.style.position = 'relative';
      const rect = item.getBoundingClientRect();
      const size = Math.max(rect.width, rect.height);
      const ripple = document.createElement('span');
      ripple.className = 'sidebar-ripple';
      ripple.style.cssText = `width:${size}px;height:${size}px;left:${e.clientX - rect.left - size / 2}px;top:${e.clientY - rect.top - size / 2}px;`;
      item.appendChild(ripple);
      ripple.addEventListener('animationend', () => ripple.remove());
    });
  }

  /* ── 12. NOTICE → TOAST redirect ─────────────────────────────────────── */
  function wireNoticeDismiss() {
    const notice = $('notice');
    if (!notice) return;
    let dismissTimer;

    // Intercept notice mutations and redirect to toast
    new MutationObserver(() => {
      clearTimeout(dismissTimer);
      if (!notice.hidden && notice.textContent.trim()) {
        const isError = notice.classList.contains('error');
        const msg = notice.textContent.trim();
        // Show toast
        showToast(msg, isError);
        // Hide the original banner immediately (keeps accessibility live region)
        dismissTimer = setTimeout(() => {
          notice.style.transition = 'opacity 0.4s ease';
          notice.style.opacity = '0';
          setTimeout(() => { notice.hidden = true; notice.style.opacity = ''; notice.style.transition = ''; }, 400);
        }, isError ? 600 : 200);
      }
    }).observe(notice, { attributes: true, attributeFilter: ['hidden'], childList: true, characterData: true });
  }

  /* ── 13. REPAIR PATCH SYNTAX HIGHLIGHT ──────────────────────────────── */
  function wireRepairHighlight() {
    const observer = new MutationObserver(mutations => {
      mutations.forEach(m => {
        m.addedNodes.forEach(node => {
          if (node.nodeType !== 1) return;
          const patches = node.classList?.contains('repair-patch')
            ? [node]
            : [...(node.querySelectorAll?.('.repair-patch') || [])];
          patches.forEach(patch => {
            if (patch.dataset.highlighted) return;
            patch.dataset.highlighted = '1';
            const raw = patch.textContent;
            const lines = raw.split('\n');
            patch.innerHTML = '';
            lines.forEach(line => {
              const span = document.createElement('span');
              span.style.display = 'block';
              span.textContent = line || '\u00a0';
              if (line.startsWith('+') && !line.startsWith('+++')) {
                span.style.background = 'var(--diff-add)';
                span.style.color = 'var(--accent)';
              } else if (line.startsWith('-') && !line.startsWith('---')) {
                span.style.background = 'var(--diff-rem)';
                span.style.color = 'var(--danger)';
              } else if (line.startsWith('@@')) {
                span.style.color = 'var(--cyan)';
              } else if (line.startsWith('+++') || line.startsWith('---')) {
                span.style.color = 'var(--text-2)';
                span.style.fontWeight = '600';
              }
              patch.appendChild(span);
            });
          });
        });
      });
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  /* ── 14. TAB SCROLL RESET ────────────────────────────────────────────── */
  function wireTabScrollReset() {
    const content = qs('.analysis-content');
    if (!content) return;
    qsa('[role=tabpanel]').forEach(panel => {
      new MutationObserver(() => {
        if (!panel.hidden) content.scrollTop = 0;
      }).observe(panel, { attributes: true, attributeFilter: ['hidden'] });
    });
  }

  /* ── 15. TOAST SYSTEM ────────────────────────────────────────────────── */
  function wireToastSystem() {
    // Container already in HTML
  }

  function showToast(message, isError = false, duration = isError ? 0 : 5000) {
    const container = $('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = 'toast';
    toast.setAttribute('role', 'alert');
    toast.setAttribute('aria-live', isError ? 'assertive' : 'polite');

    const accent = document.createElement('div');
    accent.className = 'toast-accent' + (isError ? ' error' : '');

    const text = document.createElement('div');
    text.style.flex = '1';
    text.textContent = message;

    const dismiss = document.createElement('button');
    dismiss.className = 'toast-dismiss';
    dismiss.innerHTML = '×';
    dismiss.style.cssText = 'border:0;background:transparent;cursor:pointer;color:var(--text-3);font-size:18px;line-height:1;padding:0;flex-shrink:0;';
    dismiss.setAttribute('aria-label', 'Dismiss notification');

    toast.append(accent, text, dismiss);
    container.appendChild(toast);

    const removeToast = () => {
      toast.classList.add('is-leaving');
      setTimeout(() => toast.remove(), 380);
    };

    dismiss.addEventListener('click', removeToast);
    toast.addEventListener('click', e => { if (e.target !== dismiss) removeToast(); });

    requestAnimationFrame(() => {
      void toast.offsetWidth;
      toast.classList.add('is-visible');
    });

    if (duration > 0) {
      setTimeout(removeToast, duration);
    }
  }

  window._evoToast = showToast;

  /* ── 16. SKELETON LOADERS ────────────────────────────────────────────── */
  function wireSkeletonLoaders() {
    // Intercept the analysis-content when loading state is set
    const viewStatus = $('view-status');
    if (!viewStatus) return;
    new MutationObserver(() => {
      const isLoading = viewStatus.textContent.startsWith('Loading');
      if (!isLoading) return;
      const panels = qsa('[role=tabpanel]');
      panels.forEach(panel => {
        if (panel.hidden) return;
        const existing = panel.querySelector('.empty');
        if (existing && existing.querySelector('h3')?.textContent === 'Loading…') {
          // Replace with skeleton
          const skeleton = createSkeleton(5);
          panel.replaceChildren(skeleton);
        }
      });
    }).observe(viewStatus, { childList: true, characterData: true, subtree: true });
  }

  function createSkeleton(rows = 5) {
    const wrapper = document.createElement('div');
    wrapper.className = 'skeleton-wrapper';
    for (let i = 0; i < rows; i++) {
      const row = document.createElement('div');
      row.className = 'skeleton-row';
      row.style.opacity = String(1 - i * 0.14);
      wrapper.appendChild(row);
    }
    return wrapper;
  }

  /* ── 17. GRAPH FULLSCREEN ────────────────────────────────────────────── */
  let _graphFullscreenActive = false;
  let _graphCyMoved = false;

  function openGraphFullscreen(sourceCanvas) {
    const overlay = $('graph-fullscreen');
    const targetContainer = $('graph-fullscreen-canvas');
    const closeBtn = $('graph-fullscreen-close');
    if (!overlay || !targetContainer || _graphFullscreenActive) return;

    _graphFullscreenActive = true;
    overlay.removeAttribute('hidden');
    overlay.style.animation = 'fadeIn 0.2s ease';

    // Move the cytoscape canvas DOM element into fullscreen
    const cyCanvas = sourceCanvas.querySelector('canvas') || sourceCanvas;
    // Clone the graph-canvas div and move it
    const graphCanvas = sourceCanvas.closest('.graph-canvas') || sourceCanvas;

    // Create a fresh canvas for fullscreen (cytoscape will be re-bound by app)
    const fsCanvas = graphCanvas.cloneNode(false);
    fsCanvas.style.cssText = 'flex:1;min-height:0;border-radius:0;border:0;height:auto;';
    targetContainer.replaceChildren(fsCanvas);

    // Setup controls
    const controls = $('graph-fullscreen-controls');
    if (controls) {
      controls.replaceChildren();
      const cy = window.state?.cy || (window.EvoApp?.state?.cy);
      if (cy) {
        const mkBtn = (label, fn) => {
          const b = document.createElement('button');
          b.className = 'button subtle';
          b.style.height = '28px';
          b.textContent = label;
          b.addEventListener('click', fn);
          return b;
        };
        controls.append(
          mkBtn('＋ Zoom in',  () => cy.zoom(cy.zoom() * 1.25)),
          mkBtn('－ Zoom out', () => cy.zoom(cy.zoom() / 1.25)),
          mkBtn('⊡ Fit',       () => cy.fit(undefined, 40))
        );
      }
    }

    function close() {
      _graphFullscreenActive = false;
      overlay.setAttribute('hidden', '');
      targetContainer.replaceChildren();
    }

    closeBtn.onclick = close;
    const keyClose = e => {
      if (e.key === 'Escape' || e.key === 'f' || e.key === 'F') { close(); document.removeEventListener('keydown', keyClose); }
    };
    document.addEventListener('keydown', keyClose);
  }

  /* ── 18. SHORTCUT OVERLAY ────────────────────────────────────────────── */
  function wireShortcutOverlay() {
    const overlay = $('shortcut-overlay');
    const closeBtn = $('shortcut-overlay-close');
    if (!overlay) return;

    overlay.addEventListener('click', e => {
      if (e.target === overlay) toggleShortcutOverlay(false);
    });
    if (closeBtn) closeBtn.addEventListener('click', () => toggleShortcutOverlay(false));
    document.addEventListener('keydown', e => {
      if (e.key === 'Escape' && !overlay.hidden) toggleShortcutOverlay(false);
    });
  }

  function toggleShortcutOverlay(force) {
    const overlay = $('shortcut-overlay');
    if (!overlay) return;
    const shouldShow = force !== undefined ? force : overlay.hidden;
    if (shouldShow) {
      overlay.removeAttribute('hidden');
      $('shortcut-overlay-close')?.focus();
    } else {
      overlay.setAttribute('hidden', '');
    }
  }

  /* ── 19. STAT NUMBER COUNTER ANIMATION ───────────────────────────────── */
  function wireStatCounters() {
    const statsEl = qs('.stats');
    if (!statsEl) return;

    function animateCounter(el, target) {
      const isNumeric = /^\d+(\.\d+)?$/.test(target);
      if (!isNumeric) return;
      const end = parseFloat(target);
      if (end === 0) return;
      const start = 0;
      const duration = 600;
      const startTime = performance.now();
      function step(now) {
        const elapsed = now - startTime;
        const progress = Math.min(elapsed / duration, 1);
        // Ease out cubic
        const eased = 1 - Math.pow(1 - progress, 3);
        const current = Math.round(start + (end - start) * eased);
        el.textContent = current.toLocaleString();
        if (progress < 1) requestAnimationFrame(step);
        else el.textContent = target; // restore exact original
      }
      el.textContent = '0';
      requestAnimationFrame(step);
    }

    const observer = new MutationObserver(() => {
      qsa('.stat strong', statsEl).forEach(el => {
        if (el.dataset.animated) return;
        el.dataset.animated = '1';
        const val = el.textContent.trim();
        animateCounter(el, val);
      });
    });
    observer.observe(statsEl, { childList: true, subtree: true });

    // Also handle existing stats
    qsa('.stat strong', statsEl).forEach(el => {
      if (el.dataset.animated) return;
      el.dataset.animated = '1';
      const val = el.textContent.trim();
      animateCounter(el, val);
    });
  }

  /* ── 20. STAT COUNTER trigger on workspace reveal ────────────────────── */
  function wireStatCounterTrigger() {
    const ws = $('analysis-workspace');
    if (!ws) return;
    new MutationObserver(() => {
      if (!ws.hidden) {
        setTimeout(wireStatCounters, 100);
      }
    }).observe(ws, { attributes: true, attributeFilter: ['hidden'] });
  }

  /* ── 21. GRAPH CANVAS KEYBOARD HINTS ─────────────────────────────────── */
  function wireGraphHints() {
    const graphPanel = $('panel-graph');
    if (!graphPanel) return;
    new MutationObserver(() => {
      const legend = qs('.graph-legend', graphPanel);
      if (!legend || legend.dataset.enhanced) return;
      legend.dataset.enhanced = '1';
      const tip = legend.querySelector('.legend-tip');
      if (tip) tip.textContent = 'Click node to spotlight · Scroll/pinch to zoom · Drag to pan · F for fullscreen · 0 to fit';
    }).observe(graphPanel, { childList: true, subtree: true });
  }

  /* ── Init ────────────────────────────────────────────────────────────── */
  function init() {
    wireSidebarToggle();
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
    wireRepairHighlight();
    wireTabScrollReset();
    wireToastSystem();
    wireSkeletonLoaders();
    wireShortcutOverlay();
    wireStatCounterTrigger();
    wireGraphHints();

    // Re-streamline if tabs appear late
    const ws = $('analysis-workspace');
    if (ws) {
      new MutationObserver(() => { if (!ws.hidden) streamlineTabs(); })
        .observe(ws, { attributes: true, attributeFilter: ['hidden'] });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
