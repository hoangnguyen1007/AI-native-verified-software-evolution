/* Lucian UX/UI Prompt #070, #471, #039 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/ */
(() => {
  'use strict';

  const $ = id => document.getElementById(id);
  const qs = (sel, ctx = document) => ctx.querySelector(sel);
  const qsa = (sel, ctx = document) => [...ctx.querySelectorAll(sel)];
  const reducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

  /* ── 1. LUCIAN #070 STACKING TOASTS ──────────────────────────────────── */
  function wireStackingToasts() {
    const zone = $('toast-zone');
    const live = $('toast-live');
    if (!zone) {
      window.EvoToast = { push: () => {}, dismissAll: () => {} };
      return;
    }

    const ICON = {
      ok: '<svg viewBox="0 0 18 18"><path d="M4 9.4l3.2 3.2L14 5.6"/></svg>',
      err: '<svg viewBox="0 0 18 18"><path d="M9 5v5M9 13h.01"/></svg>',
      warn: '<svg viewBox="0 0 18 18"><path d="M9 4.5L15.5 15h-13L9 4.5zm0 4v3m0 2h.01"/></svg>',
      info: '<svg viewBox="0 0 18 18"><circle cx="9" cy="9" r="6.5"/><path d="M9 8v4m0-6h.01"/></svg>'
    };
    const COLOR = {
      ok: 'var(--ux-mint)',
      err: 'var(--ux-danger)',
      warn: 'var(--ux-warn)',
      info: 'var(--ux-accent)'
    };

    const list = [];
    let open = false;
    let rafId = 0;
    let last = performance.now();

    function layout() {
      let acc = 0;
      list.forEach((t, i) => {
        const el = t.el;
        el.classList.toggle('back', !open && i > 0);
        if (open) {
          el.style.setProperty('--y', -acc + 'px');
          el.style.setProperty('--s', '1');
          el.style.setProperty('--o', '1');
          acc += el.offsetHeight + 8;
        } else {
          el.style.setProperty('--y', -(i * 12) + 'px');
          el.style.setProperty('--s', (1 - i * 0.06).toFixed(2));
          el.style.setProperty('--o', i > 2 ? '0' : '1');
        }
        el.style.zIndex = String(50 - i);
        el.inert = !open && i > 0;
      });
    }

    function remove(t, dir) {
      const i = list.indexOf(t);
      if (i < 0) return;
      list.splice(i, 1);
      t.el.classList.add('leave');
      if (dir) {
        t.el.style.setProperty('--dir', String(dir));
        t.el.classList.add('side');
      }
      setTimeout(() => t.el.remove(), reducedMotion() ? 10 : 420);
      layout();
    }

    function tick(now) {
      const dt = now - last;
      last = now;
      if (!open && list.length) {
        const front = list[0];
        if (front && front.left !== Infinity) {
          front.left -= dt;
          front.el.style.setProperty('--left', Math.max(0, front.left / front.dur).toFixed(3));
          if (front.left <= 0) remove(front);
        }
      }
      if (list.length) rafId = requestAnimationFrame(tick);
      else rafId = 0;
    }

    function push(opts) {
      const cfg = typeof opts === 'string' ? { title: opts, kind: 'info' } : (opts || {});
      const kind = ICON[cfg.kind] ? cfg.kind : 'info';
      const dur = cfg.duration || (kind === 'err' ? 6000 : 3800);
      const el = document.createElement('div');
      el.className = 'u070-t enter';
      el.style.setProperty('--c', COLOR[kind]);
      el.setAttribute('role', kind === 'err' ? 'alert' : 'status');
      el.innerHTML =
        '<span class="u070-ic" aria-hidden="true">' + ICON[kind] + '</span>' +
        '<span class="u070-tx"><b></b><small></small></span>' +
        (cfg.actionLabel ? '<button class="u070-act" type="button"></button>' : '') +
        '<button class="u070-x" type="button" aria-label="Dismiss notification"><svg viewBox="0 0 14 14"><path d="M3.5 3.5l7 7M10.5 3.5l-7 7"/></svg></button>' +
        '<span class="u070-bar" aria-hidden="true"></span>';
      el.querySelector('b').textContent = String(cfg.title || 'Notice');
      const small = el.querySelector('small');
      if (cfg.detail) small.textContent = String(cfg.detail);
      else small.remove();
      if (cfg.actionLabel) {
        const actBtn = el.querySelector('.u070-act');
        actBtn.textContent = String(cfg.actionLabel);
        actBtn.addEventListener('click', () => {
          remove(toast);
          cfg.onAction?.();
        });
      }
      const toast = { el, left: dur, dur };
      zone.appendChild(el);
      list.unshift(toast);
      if (list.length > 5) remove(list[list.length - 1]);
      void el.offsetWidth;
      el.classList.remove('enter');
      layout();
      if (live) live.textContent = cfg.title + (cfg.detail ? ': ' + cfg.detail : '');
      el.querySelector('.u070-x').addEventListener('click', () => remove(toast));

      let sx = 0, dx = 0, down = false;
      el.addEventListener('pointerdown', e => {
        if (e.target.closest('button')) return;
        down = true; sx = e.clientX; dx = 0;
        try { el.setPointerCapture(e.pointerId); } catch (_) {}
        el.classList.add('drag');
      });
      el.addEventListener('pointermove', e => {
        if (!down) return;
        dx = e.clientX - sx;
        el.style.setProperty('--dx', dx + 'px');
        el.style.setProperty('--o', String(Math.max(0.2, 1 - Math.abs(dx) / 240)));
      });
      const end = () => {
        if (!down) return;
        down = false;
        el.classList.remove('drag');
        if (Math.abs(dx) > 88) remove(toast, dx > 0 ? 1 : -1);
        else { el.style.setProperty('--dx', '0px'); layout(); }
      };
      el.addEventListener('pointerup', end);
      el.addEventListener('pointercancel', end);

      if (!rafId) {
        last = performance.now();
        rafId = requestAnimationFrame(tick);
      }
    }

    const setOpen = v => { if (open === v) return; open = v; layout(); };
    zone.addEventListener('pointerenter', e => { if (e.pointerType === 'mouse') setOpen(true); });
    zone.addEventListener('pointerleave', e => { if (e.pointerType === 'mouse' && !zone.contains(document.activeElement)) setOpen(false); });
    zone.addEventListener('focusin', () => setOpen(true));
    zone.addEventListener('focusout', e => { if (!zone.contains(e.relatedTarget)) setOpen(false); });

    window.EvoToast = {
      push,
      dismissAll: () => list.slice().forEach(t => remove(t))
    };
  }

  /* ── 2. LUCIAN #471 COMMAND K KEYCAP INTERACTION ─────────────────────── */
  function wireCommandKKeycaps() {
    const root = $('command-k-wrap');
    const b = $('command-palette-trigger');
    if (!root || !b) return;
    const caps = qsa('.u471-k', root);
    if (caps.length < 2) return;
    const [mod, key] = caps;
    const plat = navigator.userAgentData?.platform || navigator.platform || navigator.userAgent || '';
    const mac = /mac|iphone|ipad|ipod/i.test(plat);
    if (mac) {
      mod.firstElementChild.textContent = '⌘';
      mod.classList.remove('is-wide');
    } else {
      mod.firstElementChild.textContent = 'Ctrl';
      mod.classList.add('is-wide');
    }
    b.setAttribute('aria-keyshortcuts', mac ? 'Meta+K' : 'Control+K');

    let modHeld = false, pressed = false;
    const later = (fn, ms) => setTimeout(() => { if (root.isConnected) fn(); }, ms);
    const down = cap => { cap.classList.add('is-down'); cap._at = performance.now(); };
    const up = cap => {
      const left = 140 - (performance.now() - (cap._at || 0));
      clearTimeout(cap._t);
      if (left > 0) cap._t = later(() => cap.classList.remove('is-down'), left);
      else cap.classList.remove('is-down');
    };
    const answer = () => {
      if (reducedMotion()) return;
      const br = b.getBoundingClientRect();
      const kr = qs('.u471-ks', root)?.getBoundingClientRect();
      if (kr && br.width) {
        const rp = document.createElement('span');
        rp.className = 'u471-rp';
        rp.style.left = (kr.left + kr.width / 2 - br.left) + 'px';
        rp.style.top = (kr.top + kr.height / 2 - br.top) + 'px';
        b.appendChild(rp);
        later(() => rp.remove(), 780);
      }
      b.classList.remove('is-pop');
      void b.offsetWidth;
      b.classList.add('is-hit', 'is-pop');
      clearTimeout(b._t);
      b._t = later(() => b.classList.remove('is-hit'), 400);
      later(() => b.classList.remove('is-pop'), 580);
    };
    const chord = () => {
      key.classList.remove('is-ask');
      const wasHeld = mod.classList.contains('is-down');
      if (!wasHeld) down(mod);
      later(() => {
        down(key);
        answer();
        later(() => { up(key); if (!modHeld) up(mod); }, reducedMotion() ? 60 : 160);
      }, wasHeld || reducedMotion() ? 0 : 65);
    };
    const isMod = e => e.key === 'Meta' || e.key === 'Control';

    window.addEventListener('keydown', e => {
      if (isMod(e) && !e.repeat) {
        const tag = document.activeElement?.tagName;
        if (!['INPUT', 'TEXTAREA', 'SELECT'].includes(tag)) {
          modHeld = true;
          down(mod);
          key.classList.add('is-ask');
        }
      } else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k' && !e.altKey && !e.shiftKey) {
        chord();
      }
    });
    window.addEventListener('keyup', e => {
      if (isMod(e)) {
        modHeld = false;
        key.classList.remove('is-ask');
        up(mod);
      }
    });
    window.addEventListener('blur', () => {
      modHeld = false;
      key.classList.remove('is-ask');
      mod.classList.remove('is-down');
      key.classList.remove('is-down');
      b.classList.remove('is-down');
    });
    b.addEventListener('pointerdown', e => {
      if (e.button !== 0) return;
      pressed = true;
      b.classList.add('is-down');
      down(mod);
      later(() => { if (pressed) down(key); }, reducedMotion() ? 0 : 45);
    });
    const release = () => {
      if (!pressed) return;
      pressed = false;
      b.classList.remove('is-down');
      up(key);
      later(() => { if (!modHeld) up(mod); }, reducedMotion() ? 0 : 35);
    };
    ['pointerup', 'pointerleave', 'pointercancel'].forEach(ev => b.addEventListener(ev, release));
    b.addEventListener('click', e => { if (e.detail === 0) chord(); else answer(); });
  }

  /* ── 3. LUCIAN #039 SLIDING PILL TABS ────────────────────────────────── */
  function wireSlidingTabs() {
    const tablist = $('tabs');
    const pill = $('tabs-pill');
    if (!tablist || !pill) return;

    function sync(animate = true) {
      const active = qs('[role=tab][aria-selected="true"]:not([hidden])', tablist);
      if (!active || active.offsetWidth === 0) {
        pill.style.opacity = '0';
        return;
      }
      pill.style.opacity = '1';
      if (!animate || reducedMotion()) pill.style.transition = 'none';
      pill.style.setProperty('--x', active.offsetLeft + 'px');
      pill.style.setProperty('--y', (active.offsetTop - 4) + 'px');
      pill.style.setProperty('--w', active.offsetWidth + 'px');
      pill.style.height = active.offsetHeight + 'px';
      if (!animate || reducedMotion()) {
        void pill.offsetWidth;
        pill.style.transition = '';
      }
    }

    const mo = new MutationObserver(() => requestAnimationFrame(() => sync(true)));
    mo.observe(tablist, { subtree: true, attributes: true, attributeFilter: ['aria-selected', 'hidden'] });
    if (typeof ResizeObserver === 'function') {
      new ResizeObserver(() => sync(false)).observe(tablist);
    }
    window.addEventListener('resize', () => sync(false));
    requestAnimationFrame(() => sync(false));
  }

  /* ── 4. KEYBOARD SHORTCUTS ───────────────────────────────────────────── */
  function wireExtraShortcuts() {
    document.addEventListener('keydown', e => {
      if ($('repository-workspace')?.hidden) return;
      const tag = document.activeElement?.tagName;
      const inInput = ['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) ||
                      document.activeElement?.isContentEditable;

      if (qs('dialog[open]')) return;
      if (inInput || e.ctrlKey || e.metaKey || e.altKey) return;
      const ws = $('analysis-workspace');

      if (e.key === '?') {
        e.preventDefault();
        toggleShortcutOverlay();
        return;
      }

      if (e.key === 'f' || e.key === 'F') {
        const graphPanel = $('panel-graph');
        if (graphPanel && !graphPanel.hidden) {
          e.preventDefault();
          qs('.graph-fullscreen-button', graphPanel)?.click();
        }
        return;
      }

      if (e.key === 'c' || e.key === 'C') {
        const ctx = $('context');
        if (ctx && ws && !ws.hidden) { e.preventDefault(); ctx.focus(); }
        return;
      }

      if (!ws || ws.hidden) return;

      const map = {
        g: 'tab-graph',
        o: 'tab-overview',
        r: 'tab-policy',
        m: 'view-metrics',
        s: 'view-structure',
        p: 'view-repomap',
        v: 'view-coverage',
        a: 'tab-architecture-chat'
      };
      const targetId = map[e.key.toLowerCase()];
      if (targetId && $(targetId) && !$(targetId).hidden) {
        e.preventDefault();
        $(targetId).click();
      }
    });
  }

  /* ── 5. REPAIR PATCH SYNTAX HIGHLIGHT ────────────────────────────────── */
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
            patch.textContent = '';
            lines.forEach(line => {
              const span = document.createElement('span');
              span.className = 'diff-line' + (
                line.startsWith('+') && !line.startsWith('+++') ? ' diff-add'
                : line.startsWith('-') && !line.startsWith('---') ? ' diff-del'
                : line.startsWith('@@') ? ' diff-chunk' : ''
              );
              span.textContent = line || '\u00a0';
              patch.appendChild(span);
            });
          });
        });
      });
    });
    observer.observe(document.body, { childList: true, subtree: true });
  }

  /* ── 6. TAB SCROLL RESET ─────────────────────────────────────────────── */
  function wireTabScrollReset() {
    const content = qs('.analysis-content');
    if (!content) return;
    qsa('[role=tabpanel]').forEach(panel => {
      new MutationObserver(() => {
        if (!panel.hidden) content.scrollTop = 0;
      }).observe(panel, { attributes: true, attributeFilter: ['hidden'] });
    });
  }

  /* ── 7. COMMAND PALETTE & SHORTCUT OVERLAY ───────────────────────────── */
  function appendHighlighted(parent, text, query) {
    const value = String(text ?? '');
    if (!query) { parent.textContent = value; return; }
    const idx = value.toLowerCase().indexOf(query.toLowerCase());
    if (idx < 0) { parent.textContent = value; return; }
    parent.append(
      document.createTextNode(value.slice(0, idx)),
      Object.assign(document.createElement('mark'), { textContent: value.slice(idx, idx + query.length) }),
      document.createTextNode(value.slice(idx + query.length))
    );
  }

  function wireCommandPalette() {
    const dialog = $('command-palette');
    const input = $('command-palette-input');
    const list = $('command-palette-list');
    const trigger = $('command-palette-trigger');
    const closeBtn = $('command-palette-close');
    if (!dialog || !input || !list) return;

    let items = [];
    let selectedIndex = 0;
    let lastFocus = null;

    function baseCommands() {
      const wb = window.EvoWorkbench;
      const app = window.EvoApp;
      const go = tab => app?.loadTab?.(tab);
      const cmds = [
        { category: 'Navigate', label: 'Overview & Health Hero', hint: 'O', run: () => go('overview') },
        { category: 'Navigate', label: 'Structure Explorer', hint: 'S', run: () => go('structure') },
        { category: 'Navigate', label: 'Interactive Dependency Graph', hint: 'G', run: () => go('graph') },
        { category: 'Navigate', label: 'RepoMap (PPR) & Captured Source', hint: 'P', run: () => go('repomap') },
        { category: 'Navigate', label: 'Connections & Polyglot Bridges', hint: 'Bridges', run: () => go('connections') },
        { category: 'Navigate', label: 'Spring Wiring & Beans', hint: 'Spring', run: () => go('spring') },
        { category: 'Navigate', label: 'Spring Configurations (MUST/MAY)', hint: 'SAT', run: () => go('configurations') },
        { category: 'Navigate', label: 'Gaps & Coverage Remediation', hint: 'V', run: () => go('coverage') },
        { category: 'Navigate', label: 'Findings & Verified Repair', hint: 'R', run: () => go('policy') },
        { category: 'Navigate', label: 'Score, Metrics & Hotspots', hint: 'M', run: () => go('metrics') },
        { category: 'Navigate', label: 'Change Impact (Blast Radius)', hint: 'Impact', run: () => go('impact') },
        { category: 'Navigate', label: 'Insights, Tangles & SDP', hint: 'Insights', run: () => go('insights') },
        { category: 'Navigate', label: 'AI Architecture Studio', hint: 'A', run: () => go('architecture-chat') },
        { category: 'Action', label: 'Auto-detect Policy Rules', hint: 'Policy', run: () => {
          go('metrics')?.then?.(() => setTimeout(() => qs('.auto-policy-button')?.click(), 90));
        }},
        { category: 'Action', label: 'Ask AI to Explain Hotspots & Gaps', hint: 'AI Agent', run: () => {
          if (wb?.askAiPrompt) wb.askAiPrompt('Inspect top architectural hotspots, cyclic dependencies, SDP violations, and coverage gaps in this snapshot and suggest safe refactoring steps.');
          else go('architecture-chat');
        }},
        { category: 'Action', label: 'Toggle Light / Dark Theme', hint: 'Theme', run: () => $('theme-toggle')?.click() },
        { category: 'Action', label: 'Keyboard Shortcuts Reference', hint: '?', run: () => toggleShortcutOverlay(true) }
      ];
      if (!app?.state?.graph) return cmds.filter(c => c.category === 'Action');
      return cmds;
    }

    let searchTimer = null;
    async function refreshList() {
      const q = input.value.trim();
      const lower = q.toLowerCase();
      const matched = baseCommands().filter(c =>
        !lower || c.label.toLowerCase().includes(lower) || c.category.toLowerCase().includes(lower) || c.hint.toLowerCase().includes(lower));
      let symbols = [];
      if (q.length >= 2 && window.EvoWorkbench?.searchSymbols) {
        try { symbols = await window.EvoWorkbench.searchSymbols(q); } catch (_) { symbols = []; }
      }
      items = [
        ...matched,
        ...symbols.map(sym => ({
          category: sym.kind || 'Symbol',
          label: sym.label || sym.qualifiedName || sym.identity,
          hint: sym.group || 'Focus graph',
          run: () => window.EvoWorkbench?.focusSymbol?.(sym)
        }))
      ];
      selectedIndex = 0;
      renderList(q);
    }

    function renderList(query = '') {
      list.textContent = '';
      if (!items.length) {
        const empty = document.createElement('div');
        empty.className = 'command-palette-empty';
        empty.textContent = 'No matching commands or symbols in this context.';
        list.appendChild(empty);
        return;
      }
      items.forEach((item, index) => {
        const row = document.createElement('button');
        row.type = 'button';
        row.className = 'command-palette-item' + (index === selectedIndex ? ' is-selected' : '');
        row.setAttribute('role', 'option');
        row.setAttribute('aria-selected', String(index === selectedIndex));
        const cat = document.createElement('span');
        cat.className = 'command-palette-cat';
        cat.textContent = item.category;
        const lbl = document.createElement('span');
        lbl.className = 'command-palette-label';
        appendHighlighted(lbl, item.label, query);
        const hint = document.createElement('kbd');
        hint.className = 'command-palette-hint';
        hint.textContent = item.hint;
        row.append(cat, lbl, hint);
        row.addEventListener('click', () => runItem(item));
        row.addEventListener('mouseenter', () => { selectedIndex = index; highlightSelection(); });
        list.appendChild(row);
      });
    }

    function highlightSelection() {
      qsa('.command-palette-item', list).forEach((el, idx) => {
        el.classList.toggle('is-selected', idx === selectedIndex);
        el.setAttribute('aria-selected', String(idx === selectedIndex));
        if (idx === selectedIndex) el.scrollIntoView({ block: 'nearest' });
      });
    }

    function closePalette() {
      if (dialog.open) dialog.close();
      if (lastFocus && document.contains(lastFocus)) lastFocus.focus({ preventScroll: true });
      lastFocus = null;
    }

    function runItem(item) {
      if (!item) return;
      if (dialog.open) dialog.close();
      lastFocus = null;
      item.run();
    }

    function openPalette() {
      if (dialog.open) return;
      lastFocus = document.activeElement;
      input.value = '';
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
      refreshList();
      input.focus();
    }

    window.EvoCommandPalette = { open: openPalette, close: closePalette };
    trigger?.addEventListener('click', openPalette);
    closeBtn?.addEventListener('click', closePalette);
    dialog.addEventListener('click', e => { if (e.target === dialog) closePalette(); });
    dialog.addEventListener('cancel', e => { e.preventDefault(); closePalette(); });
    input.addEventListener('input', () => {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(refreshList, 90);
    });
    input.addEventListener('keydown', e => {
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        if (items.length) { selectedIndex = (selectedIndex + 1) % items.length; highlightSelection(); }
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (items.length) { selectedIndex = (selectedIndex - 1 + items.length) % items.length; highlightSelection(); }
      } else if (e.key === 'Enter') {
        e.preventDefault();
        if (items[selectedIndex]) runItem(items[selectedIndex]);
      } else if (e.key === 'Escape') {
        e.preventDefault();
        closePalette();
      }
    });
    document.addEventListener('keydown', e => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        if (dialog.open) closePalette();
        else openPalette();
      }
    });
  }

  let shortcutReturnFocus = null;
  function wireShortcutOverlay() {
    const overlay = $('shortcut-overlay');
    const closeBtn = $('shortcut-overlay-close') || $('shortcut-close');
    const trigger = $('shortcuts-trigger');
    if (!overlay) return;

    trigger?.addEventListener('click', () => toggleShortcutOverlay(true));
    overlay.addEventListener('click', e => {
      if (e.target === overlay) toggleShortcutOverlay(false);
    });
    closeBtn?.addEventListener('click', () => toggleShortcutOverlay(false));
    document.addEventListener('keydown', e => {
      if (e.key === 'Escape' && !overlay.hidden) toggleShortcutOverlay(false);
    });
  }

  function toggleShortcutOverlay(force) {
    const overlay = $('shortcut-overlay');
    if (!overlay) return;
    const shouldShow = force !== undefined ? force : overlay.hidden;
    if (shouldShow) {
      shortcutReturnFocus = document.activeElement;
      overlay.removeAttribute('hidden');
      ($('shortcut-overlay-close') || $('shortcut-close'))?.focus();
    } else {
      overlay.setAttribute('hidden', '');
      if (shortcutReturnFocus && document.contains(shortcutReturnFocus)) {
        shortcutReturnFocus.focus({ preventScroll: true });
      }
      shortcutReturnFocus = null;
    }
  }

  function wireThemeToast() {
    $('theme-toggle')?.addEventListener('click', () => {
      setTimeout(() => {
        const current = document.documentElement.dataset.theme === 'light' ? 'Light' : 'Dark';
        window.EvoToast?.push({ kind: 'info', title: current + ' theme active', detail: 'Tokens switched cleanly.', duration: 2200 });
      }, 30);
    });
  }

  function init() {
    wireStackingToasts();
    wireCommandKKeycaps();
    wireSlidingTabs();
    wireExtraShortcuts();
    wireCommandPalette();
    wireRepairHighlight();
    wireTabScrollReset();
    wireShortcutOverlay();
    wireThemeToast();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
