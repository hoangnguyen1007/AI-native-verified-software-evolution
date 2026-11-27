/* Lucian UX/UI Prompt #417, #040 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#417 */
(() => {
  'use strict';

  const STORAGE_KEY = 'evo_sidebar_rail';
  const MOBILE_MQ = window.matchMedia('(max-width: 760px)');
  const reducedMotion = () => Boolean(window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);
  const $ = id => document.getElementById(id);
  const qs = (sel, ctx = document) => ctx.querySelector(sel);
  const qsa = (sel, ctx = document) => [...ctx.querySelectorAll(sel)];

  const GIT_MINI_SVG =
    '<svg viewBox="0 0 24 24" aria-hidden="true">' +
      '<line x1="6" y1="3" x2="6" y2="15"/>' +
      '<circle cx="18" cy="6" r="3"/>' +
      '<circle cx="6" cy="18" r="3"/>' +
      '<path d="M18 9a9 9 0 0 1-9 9"/>' +
    '</svg>';

  const FOLDER_MINI_SVG =
    '<svg viewBox="0 0 24 24" aria-hidden="true">' +
      '<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>' +
    '</svg>';

  function deriveMonogram(name) {
    const clean = String(name || '').trim().replace(/^[@./]+/, '');
    if (!clean) return 'RP';
    const tokens = clean.split(/[\s/_.-]+/).filter(Boolean);
    if (tokens.length >= 2) {
      return (tokens[0][0] + tokens[1][0]).toUpperCase();
    }
    const camel = clean.match(/[A-Z]/g);
    if (camel && camel.length >= 2) {
      return (camel[0] + camel[1]).toUpperCase();
    }
    return clean.slice(0, 2).toUpperCase();
  }

  function classifyRunState(stateText) {
    const t = String(stateText || '').trim().toLowerCase();
    if (!t) return { tone: 'ok', label: 'Completed' };
    if (t.includes('fail') || t.includes('timed')) return { tone: 'danger', label: stateText.trim() };
    if (t.includes('cancel')) return { tone: 'muted', label: stateText.trim() };
    if (t.includes('run') || t.includes('queue')) return { tone: 'warn', label: stateText.trim() };
    return { tone: 'ok', label: stateText.trim() };
  }

  function createSpringHighlight(container) {
    const hl = document.createElement('span');
    hl.className = 'u417-hl';
    hl.setAttribute('aria-hidden', 'true');

    let last = { visible: false, x: 0, y: 0, w: 0, h: 0 };

    function applyGeometry(x, y, w, h) {
      hl.style.setProperty('--px', x.toFixed(1) + 'px');
      hl.style.setProperty('--py', y.toFixed(1) + 'px');
      hl.style.setProperty('--pw', w.toFixed(1) + 'px');
      hl.style.setProperty('--ph', h.toFixed(1) + 'px');
    }

    function ensureMounted() {
      if (!container.isConnected) return false;
      container.classList.add('u417-has-hl');
      if (hl.parentNode !== container) {
        container.prepend(hl);
        return true;
      }
      return false;
    }

    function sync(animate = true) {
      if (!container || !container.isConnected) return;
      const active = qs(':scope > .sidebar-item.active', container);
      const remounted = ensureMounted();

      if (!active || active.offsetWidth === 0 || active.offsetHeight === 0) {
        hl.classList.remove('is-show');
        last.visible = false;
        return;
      }

      const nextX = active.offsetLeft;
      const nextY = active.offsetTop;
      const nextW = active.offsetWidth;
      const nextH = active.offsetHeight;
      const shouldAnimate = animate && last.visible && !reducedMotion();

      if (!shouldAnimate) {
        hl.style.transition = 'none';
        applyGeometry(nextX, nextY, nextW, nextH);
        hl.classList.add('is-show');
        void hl.offsetHeight;
        hl.style.transition = '';
      } else if (remounted) {
        // Restore previous coordinates first so spring glide survives app.js replaceChildren()
        hl.style.transition = 'none';
        applyGeometry(last.x, last.y, last.w, last.h);
        hl.classList.add('is-show');
        void hl.offsetHeight;
        hl.style.transition = '';
        applyGeometry(nextX, nextY, nextW, nextH);
      } else {
        applyGeometry(nextX, nextY, nextW, nextH);
        hl.classList.add('is-show');
      }

      last = { visible: true, x: nextX, y: nextY, w: nextW, h: nextH };
    }

    return { sync };
  }

  function init() {
    const shell = qs('.app-shell');
    const sidebar = $('sidebar');
    const toggleBtn = $('sidebar-toggle');
    const reposEl = $('repositories');
    const historyEl = $('history');
    const historyMoreEl = $('history-more');
    const brandEl = qs('.brand', sidebar || document);
    const connectBtn = qs('.u002-connect', sidebar || document);
    const refreshBtn = qs('[data-action="refresh"]', sidebar || document);
    const bottomEl = qs('.sidebar-bottom', sidebar || document);
    const statusEl = $('connection-status');
    const innerEl = qs('.sidebar-inner', sidebar || document);

    if (!shell || !sidebar || !toggleBtn) return;

    // 1. Mount Lucian #417 round edge collapse handle as LAST child of #sidebar
    //    (keeps mobile drawer's first focusable button intact for app.js:4394).
    let edgeBtn = $('sidebar-rail-toggle');
    if (!edgeBtn) {
      edgeBtn = document.createElement('button');
      edgeBtn.type = 'button';
      edgeBtn.id = 'sidebar-rail-toggle';
      edgeBtn.className = 'u417-edge';
      edgeBtn.setAttribute('aria-controls', 'sidebar');
      edgeBtn.innerHTML = '<svg viewBox="0 0 12 12" aria-hidden="true"><path d="M7.5 2.5 4 6l3.5 3.5"/></svg>';
      sidebar.appendChild(edgeBtn);
    }

    // 2. Mount single fixed right-anchored floating tooltip on document.body
    let tipEl = $('sidebar-rail-tip');
    if (!tipEl) {
      tipEl = document.createElement('div');
      tipEl.id = 'sidebar-rail-tip';
      tipEl.className = 'u417-tip';
      tipEl.setAttribute('role', 'tooltip');
      tipEl.setAttribute('aria-hidden', 'true');
      tipEl.innerHTML = '<span class="u417-tip-t"></span><span class="u417-tip-s"></span>';
      document.body.appendChild(tipEl);
    }
    const tipTitle = qs('.u417-tip-t', tipEl);
    const tipSub = qs('.u417-tip-s', tipEl);
    let tipAnchor = null;

    function hideTooltip() {
      tipAnchor = null;
      tipEl.classList.remove('is-open');
      tipEl.setAttribute('aria-hidden', 'true');
    }

    function showTooltip(target) {
      if (MOBILE_MQ.matches || !shell.classList.contains('sidebar-rail')) {
        hideTooltip();
        return;
      }
      const title = target?.dataset?.railTip?.trim();
      if (!title) {
        hideTooltip();
        return;
      }
      tipAnchor = target;
      tipTitle.textContent = title;
      tipSub.textContent = target.dataset.railSub?.trim() || '';

      const rect = target.getBoundingClientRect();
      if (!rect.width && !rect.height) {
        hideTooltip();
        return;
      }

      const tipH = tipEl.offsetHeight || 34;
      const centerY = rect.top + rect.height / 2;
      const clampedY = Math.max(tipH / 2 + 8, Math.min((window.innerHeight || 900) - tipH / 2 - 8, centerY));
      tipEl.style.left = Math.round(rect.right + 10) + 'px';
      tipEl.style.top = Math.round(clampedY) + 'px';
      tipEl.setAttribute('aria-hidden', 'false');
      tipEl.classList.add('is-open');
    }

    // 3. Spring active-item highlight controllers
    const repoHighlight = reposEl ? createSpringHighlight(reposEl) : null;
    const historyHighlight = historyEl ? createSpringHighlight(historyEl) : null;

    function syncHighlights(animate = true) {
      repoHighlight?.sync(animate);
      historyHighlight?.sync(animate);
    }

    // 4. Persisted rail state
    let isRail = false;
    try {
      isRail = localStorage.getItem(STORAGE_KEY) === '1';
    } catch (_) {
      isRail = false;
    }

    let expandTimer = 0;
    let resizeTimers = [];
    let decorating = false;

    function clearResizeTimers() {
      resizeTimers.forEach(id => clearTimeout(id));
      resizeTimers = [];
    }

    function syncStaticTips() {
      const desktopRail = !MOBILE_MQ.matches && isRail;
      if (brandEl) {
        brandEl.dataset.railTip = 'Evo Workbench';
        brandEl.dataset.railSub = 'Architecture intelligence';
      }
      if (connectBtn) {
        connectBtn.dataset.railTip = 'Connect repository';
        connectBtn.dataset.railSub = connectBtn.disabled ? 'Read-only server' : 'Local folder or HTTPS Git';
      }
      if (refreshBtn) {
        refreshBtn.dataset.railTip = 'Refresh history';
        refreshBtn.dataset.railSub = '';
        if (desktopRail) {
          if (refreshBtn.hasAttribute('title')) {
            refreshBtn.dataset.origTitle = refreshBtn.getAttribute('title') || 'Refresh';
            refreshBtn.removeAttribute('title');
          }
        } else if (refreshBtn.dataset.origTitle) {
          refreshBtn.setAttribute('title', refreshBtn.dataset.origTitle);
        }
      }
      if (historyMoreEl) {
        historyMoreEl.dataset.railTip = 'Load more runs';
        historyMoreEl.dataset.railSub = '';
      }
      if (bottomEl) {
        const statusText = statusEl?.textContent?.trim() || 'Connection status';
        bottomEl.dataset.railTip = statusText;
        bottomEl.dataset.railSub = '';
      }
    }

    function decorateRepositories() {
      if (!reposEl) return;
      const rows = qsa(':scope > .sidebar-item.repository-row', reposEl);
      rows.forEach((row, idx) => {
        row.style.setProperty('--u040-i', String(Math.min(idx, 8)));
        const name = qs('.repo-name', row)?.textContent?.trim() || 'Repository';
        const meta = qs('.repo-meta', row)?.textContent?.trim() || '';
        const isGit = meta.toLowerCase().includes('git');
        row.dataset.railTip = name;
        row.dataset.railSub = meta;

        let av = qs(':scope > .u417-av', row);
        if (!av) {
          av = document.createElement('span');
          av.className = 'u417-av';
          av.setAttribute('aria-hidden', 'true');
          av.innerHTML = '<span class="u417-av-txt"></span><span class="u417-av-kind"></span>';
          row.prepend(av);
        }
        const txtEl = qs('.u417-av-txt', av);
        const kindEl = qs('.u417-av-kind', av);
        const mono = deriveMonogram(name);
        if (txtEl && txtEl.textContent !== mono) txtEl.textContent = mono;
        const kindKey = isGit ? 'git' : 'local';
        if (kindEl && kindEl.dataset.kind !== kindKey) {
          kindEl.dataset.kind = kindKey;
          kindEl.innerHTML = isGit ? GIT_MINI_SVG : FOLDER_MINI_SVG;
        }
      });
    }

    function decorateHistory() {
      if (!historyEl) return;
      const rows = qsa(':scope > .sidebar-item.history-row', historyEl);
      rows.forEach((row, idx) => {
        row.style.setProperty('--u040-i', String(Math.min(idx + 3, 8)));
        const title = qs('.repo-name', row)?.textContent?.trim() || 'Analysis run';
        const meta = qs('.repo-meta', row)?.textContent?.trim() || '';
        const stateText = qs('.run-state', row)?.textContent?.trim() || '';
        const info = classifyRunState(stateText);
        row.dataset.railTip = title;
        row.dataset.railSub = meta ? `${meta} · ${info.label}` : info.label;

        let dot = qs(':scope > .u417-run-dot', row);
        if (!dot) {
          dot = document.createElement('span');
          dot.className = 'u417-run-dot';
          dot.setAttribute('aria-hidden', 'true');
          row.prepend(dot);
        }
        dot.dataset.tone = info.tone;
      });
    }

    function decorateAll(animateHighlight = true) {
      if (decorating) return;
      decorating = true;
      try {
        decorateRepositories();
        decorateHistory();
        syncStaticTips();
        syncHighlights(animateHighlight);
      } finally {
        decorating = false;
      }
    }

    function syncShellState({ animate = false, expanding = false } = {}) {
      const mobile = MOBILE_MQ.matches;
      hideTooltip();

      if (mobile) {
        shell.classList.remove('sidebar-rail', 'u040-expanding');
        edgeBtn.hidden = true;
        edgeBtn.tabIndex = -1;
        syncStaticTips();
        requestAnimationFrame(() => syncHighlights(false));
        return;
      }

      // Desktop (> 760px): harmonize cleanly with app.js without hiding or inerting sidebar
      edgeBtn.hidden = false;
      edgeBtn.tabIndex = 0;
      shell.classList.remove('sidebar-collapsed', 'mobile-sidebar-open');
      shell.classList.toggle('sidebar-rail', isRail);
      sidebar.inert = false;

      const expanded = !isRail;
      toggleBtn.setAttribute('aria-expanded', String(expanded));
      toggleBtn.setAttribute('title', expanded ? 'Collapse sidebar ([)' : 'Expand sidebar ([)');
      edgeBtn.setAttribute('aria-expanded', String(expanded));
      edgeBtn.setAttribute('aria-label', expanded ? 'Collapse sidebar to icon rail' : 'Expand sidebar');
      edgeBtn.title = expanded ? 'Collapse sidebar ([)' : '';
      edgeBtn.dataset.railTip = expanded ? '' : 'Expand sidebar';
      edgeBtn.dataset.railSub = expanded ? '' : 'Shortcut: [';

      syncStaticTips();

      clearTimeout(expandTimer);
      if (expanding && !reducedMotion()) {
        shell.classList.remove('u040-expanding');
        void shell.offsetWidth;
        shell.classList.add('u040-expanding');
        expandTimer = setTimeout(() => shell.classList.remove('u040-expanding'), 720);
      } else {
        shell.classList.remove('u040-expanding');
      }

      // Keep active highlight & Cytoscape canvas synced across grid-template-columns transition
      clearResizeTimers();
      requestAnimationFrame(() => syncHighlights(false));
      if (animate && !reducedMotion()) {
        resizeTimers.push(
          setTimeout(() => {
            syncHighlights(false);
            window.EvoApp?.state?.cy?.resize?.();
          }, 260),
          setTimeout(() => {
            syncHighlights(false);
            window.EvoApp?.state?.cy?.resize?.();
          }, 640)
        );
      } else {
        window.EvoApp?.state?.cy?.resize?.();
      }
    }

    function setRail(next, persist = true) {
      const target = Boolean(next);
      const wasRail = isRail;
      isRail = target;
      if (persist) {
        try {
          localStorage.setItem(STORAGE_KEY, isRail ? '1' : '0');
        } catch (_) {}
      }
      syncShellState({ animate: true, expanding: wasRail && !isRail });
    }

    // 5. Intercept topbar #sidebar-toggle in capture phase on desktop (> 760px)
    //    so app.js doesn't add .sidebar-collapsed / sidebar.inert = true.
    //    Mobile (<= 760px) passes directly through to app.js mobile drawer!
    toggleBtn.addEventListener('click', e => {
      if (MOBILE_MQ.matches) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      setRail(!isRail, true);
    }, true);

    edgeBtn.addEventListener('click', e => {
      if (MOBILE_MQ.matches) return;
      e.preventDefault();
      setRail(!isRail, true);
    });

    // 6. Keyboard shortcut `[` when outside inputs / dialogs
    document.addEventListener('keydown', e => {
      if (MOBILE_MQ.matches) return;
      if (e.key !== '[' || e.ctrlKey || e.metaKey || e.altKey) return;
      if (qs('dialog[open]')) return;
      const overlay = $('shortcut-overlay');
      if (overlay && !overlay.hidden) return;
      const active = document.activeElement;
      const tag = active?.tagName;
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) || active?.isContentEditable) return;
      e.preventDefault();
      setRail(!isRail, true);
    });

    // 7. Hover / focus delegation for floating rail tooltips
    sidebar.addEventListener('pointerover', e => {
      if (e.pointerType === 'touch') return;
      const target = e.target?.closest?.('[data-rail-tip]');
      if (!target || !sidebar.contains(target)) return;
      if (target !== tipAnchor) showTooltip(target);
    });
    sidebar.addEventListener('pointerout', e => {
      if (!tipAnchor) return;
      const next = e.relatedTarget?.closest?.('[data-rail-tip]');
      if (!next || !sidebar.contains(next)) {
        hideTooltip();
      }
    });
    sidebar.addEventListener('focusin', e => {
      const target = e.target?.closest?.('[data-rail-tip]');
      if (target && sidebar.contains(target)) showTooltip(target);
    });
    sidebar.addEventListener('focusout', e => {
      if (!sidebar.contains(e.relatedTarget)) hideTooltip();
    });
    sidebar.addEventListener('pointerdown', hideTooltip);
    innerEl?.addEventListener('scroll', hideTooltip, { passive: true });
    window.addEventListener('scroll', hideTooltip, { passive: true });

    // 8. Re-sync after app.js resize handler & grid transition
    shell.addEventListener('transitionend', e => {
      if (e.target === shell && e.propertyName === 'grid-template-columns') {
        syncHighlights(false);
        window.EvoApp?.state?.cy?.resize?.();
      }
    });

    window.addEventListener('resize', () => {
      syncShellState({ animate: false, expanding: false });
    });

    if (typeof MOBILE_MQ.addEventListener === 'function') {
      MOBILE_MQ.addEventListener('change', () => {
        syncShellState({ animate: false, expanding: false });
      });
    }

    // 9. Observe app.js dynamic re-renders in #repositories, #history, & connection status
    let listRaf = 0;
    const scheduleDecorate = () => {
      if (decorating || listRaf) return;
      listRaf = requestAnimationFrame(() => {
        listRaf = 0;
        decorateAll(true);
      });
    };

    const listObserver = new MutationObserver(records => {
      if (decorating) return;
      const relevant = records.some(r => {
        const t = r.target;
        return t && !t.classList?.contains('u417-hl') && !t.classList?.contains('u417-av') && !t.classList?.contains('u417-run-dot');
      });
      if (relevant) scheduleDecorate();
    });

    if (reposEl) listObserver.observe(reposEl, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
    if (historyEl) listObserver.observe(historyEl, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
    if (statusEl) {
      new MutationObserver(syncStaticTips).observe(statusEl, { childList: true, characterData: true, subtree: true });
    }
    if (connectBtn) {
      new MutationObserver(syncStaticTips).observe(connectBtn, { attributes: true, attributeFilter: ['disabled'] });
    }

    if (typeof ResizeObserver === 'function') {
      const ro = new ResizeObserver(() => syncHighlights(false));
      if (reposEl) ro.observe(reposEl);
      if (historyEl) ro.observe(historyEl);
      ro.observe(sidebar);
    }

    // 10. Initial paint without bounce jump
    shell.classList.add('u417-no-anim');
    syncShellState({ animate: false, expanding: false });
    decorateAll(false);
    void shell.offsetWidth;
    requestAnimationFrame(() => {
      shell.classList.remove('u417-no-anim');
      syncHighlights(false);
    });

    window.EvoSidebar = {
      isRail: () => isRail,
      setRail,
      toggle: () => setRail(!isRail, true),
      sync: () => decorateAll(false)
    };
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
