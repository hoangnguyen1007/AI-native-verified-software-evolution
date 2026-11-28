/* Lucian UX/UI Prompt #002, #008, #027, #031, #039, #070, #083, #098, #099, #219, #228, #430, #440, #450, #454, #471 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/ */
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
      let clearance = 0;
      if (list.length) {
        if (open) {
          clearance = acc + 10;
        } else {
          const frontH = list[0].el.offsetHeight || 64;
          const visibleBack = Math.min(2, list.length - 1);
          clearance = frontH + visibleBack * 12 + 14;
        }
      }
      document.documentElement.style.setProperty('--toast-clearance', clearance + 'px');
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
        if (ctx && ws && !ws.hidden) {
          e.preventDefault();
          const shell = $('context-picker-shell');
          if (shell && !shell.hidden && window.EvoContextPicker?.focus) {
            window.EvoContextPicker.focus();
          } else {
            ctx.focus();
          }
        }
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
    qsa('[role=tabpanel]').forEach(panel => {
      new MutationObserver(() => {
        if (!panel.hidden) {
          if (content) content.scrollTop = 0;
          window.EvoBackToTop?.sync?.();
        }
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
      const caps = app?.state?.capabilities || {};
      const go = tab => app?.loadTab?.(tab);
      const hasSpring = !document.querySelector('[data-spring-view]')?.hidden;
      const cmds = [
        { category: 'Navigate', label: 'Overview & Health Hero', hint: 'O', requiresGraph: true, run: () => go('overview') },
        { category: 'Navigate', label: 'Structure Explorer', hint: 'S', requiresGraph: true, run: () => go('structure') },
        { category: 'Navigate', label: 'Interactive Dependency Graph', hint: 'G', requiresGraph: true, run: () => go('graph') },
        { category: 'Navigate', label: 'RepoMap (PPR) & Captured Source', hint: 'P', requiresGraph: true, run: () => go('repomap') },
        { category: 'Navigate', label: 'Connections & Polyglot Bridges', hint: 'Bridges', requiresGraph: true, run: () => go('connections') },
        ...(hasSpring ? [
          { category: 'Navigate', label: 'Spring Wiring & Beans', hint: 'Spring', requiresGraph: true, run: () => go('spring') },
          { category: 'Navigate', label: 'Spring Configurations (MUST/MAY)', hint: 'SAT', requiresGraph: true, run: () => go('configurations') }
        ] : []),
        { category: 'Navigate', label: 'Gaps & Coverage Remediation', hint: 'V', requiresGraph: true, run: () => go('coverage') },
        { category: 'Navigate', label: 'Findings & Verified Repair Studio', hint: 'R', requiresGraph: true, run: () => go('policy') },
        { category: 'Navigate', label: 'Score, Metrics & Hotspots', hint: 'M', requiresGraph: true, run: () => go('metrics') },
        { category: 'Navigate', label: 'Change Impact (Blast Radius)', hint: 'Impact', requiresGraph: true, run: () => go('impact') },
        ...(caps.architectureInsightsEnabled ? [
          { category: 'Navigate', label: 'Insights, Tangles & SDP', hint: 'Insights', requiresGraph: true, run: () => go('insights') }
        ] : []),
        ...(caps.architectureChatEnabled ? [
          { category: 'Navigate', label: 'AI Architecture Studio', hint: 'A', requiresGraph: true, run: () => go('architecture-chat') }
        ] : []),
        ...(caps.suggestedPolicyEnabled ? [{ category: 'Action', label: 'Auto-detect Policy Rules', hint: 'Policy', requiresGraph: true, run: () => {
          go('metrics')?.then?.(() => setTimeout(() => qs('.auto-policy-button')?.click(), 90));
        }}] : []),
        ...(caps.architectureChatEnabled ? [{ category: 'Action', label: 'Ask AI to Explain Hotspots & Gaps', hint: 'AI Agent', requiresGraph: true, run: () => {
          if (wb?.askAiPrompt) wb.askAiPrompt('Inspect top architectural hotspots, cyclic dependencies, SDP violations, and coverage gaps in this snapshot and suggest safe refactoring steps.');
          else go('architecture-chat');
        }}] : []),
        { category: 'Action', label: 'Connect AI Agent (MCP Setup)', hint: 'MCP', requiresGraph: false, run: () => wb?.openMcpSetup?.() },
        { category: 'Action', label: 'Connect Repository', hint: 'Repo', requiresGraph: false, run: () => qs('[data-action=connect]')?.click() },
        { category: 'Action', label: 'Toggle Light / Dark Theme', hint: 'Theme', requiresGraph: false, run: () => $('theme-toggle')?.click() },
        { category: 'Action', label: 'Keyboard Shortcuts Reference', hint: '?', requiresGraph: false, run: () => toggleShortcutOverlay(true) }
      ];
      if (!app?.state?.graph) return cmds.filter(c => !c.requiresGraph);
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
        const otherModalOpen = ['connect-dialog', 'inspector', 'disconnect-dialog', 'github-session-dialog']
          .some(id => $(id)?.open);
        if (otherModalOpen) return;
        e.preventDefault();
        e.stopPropagation();
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

  /* ── 8. LUCIAN #219 CIRCULAR-REVEAL THEME TRANSITION ─────────────────── */
  function wireThemeReveal() {
    window.EvoThemeReveal = {
      trigger(btn, nextTheme, applyFn) {
        const prevBg = getComputedStyle(document.documentElement).getPropertyValue('--bg').trim() || '#0a0e0d';
        applyFn();
        if (reducedMotion() || !btn || typeof document.body?.appendChild !== 'function') return true;
        qsa('.u219-veil').forEach(old => old.remove());
        const rect = btn.getBoundingClientRect();
        const cx = rect.width ? rect.left + rect.width / 2 : window.innerWidth - 32;
        const cy = rect.height ? rect.top + rect.height / 2 : 28;
        const maxR = Math.hypot(Math.max(cx, window.innerWidth - cx), Math.max(cy, window.innerHeight - cy)) + 24;
        const veil = document.createElement('div');
        veil.className = 'u219-veil';
        veil.setAttribute('aria-hidden', 'true');
        veil.style.setProperty('--cx', cx.toFixed(0) + 'px');
        veil.style.setProperty('--cy', cy.toFixed(0) + 'px');
        veil.style.setProperty('--r', maxR.toFixed(0) + 'px');
        veil.style.setProperty('--veil-from', prevBg);
        veil.dataset.to = nextTheme;
        document.body.appendChild(veil);
        void veil.offsetWidth;
        veil.classList.add('is-anim');
        setTimeout(() => veil.remove(), 740);
        return true;
      }
    };
  }

  /* ── 9. LUCIAN #228 CURSOR-LIT GLOWING BORDERS (DE-CARDIFIED LISTS) ──── */
  function wireSpotlightCards() {
    const GRID_SELECTOR = '.gaps-summary-grid, .repomap-file-list';
    const CARD_SELECTOR = '.gap-group-card, .repomap-file-card';
    const activeGrids = new WeakMap();

    function getState(grid) {
      let s = activeGrids.get(grid);
      if (!s) {
        s = { x: -500, y: -500, o: 0, oT: 0, raf: 0, last: 0 };
        activeGrids.set(grid, s);
      }
      return s;
    }

    function apply(grid, s) {
      const rb = grid.getBoundingClientRect();
      const vh = window.innerHeight || 900;
      const cards = grid.querySelectorAll(CARD_SELECTOR);
      cards.forEach(card => {
        const r = card.getBoundingClientRect();
        if (s.o > 0 && (r.bottom < -260 || r.top > vh + 260)) return;
        const lx = s.x - (r.left - rb.left);
        const ly = s.y - (r.top - rb.top);
        card.classList.add('u228-glow');
        card.style.setProperty('--cx', lx.toFixed(1) + 'px');
        card.style.setProperty('--cy', ly.toFixed(1) + 'px');
        card.style.setProperty('--o', s.o.toFixed(3));
      });
    }

    function kick(grid, s) {
      if (s.raf) return;
      s.last = performance.now();
      const frame = now => {
        s.raf = 0;
        if (!grid.isConnected) return;
        const dt = Math.min(0.05, Math.max(0, (now - s.last) / 1000));
        s.last = now;
        s.o += (s.oT - s.o) * (reducedMotion() ? 1 : Math.min(1, dt * (s.oT ? 8 : 2.5)));
        if (Math.abs(s.oT - s.o) <= 0.005) {
          s.o = s.oT;
          apply(grid, s);
          return;
        }
        apply(grid, s);
        s.raf = requestAnimationFrame(frame);
      };
      s.raf = requestAnimationFrame(frame);
    }

    let currentGrid = null;
    document.addEventListener('pointermove', e => {
      if (e.pointerType === 'touch' || reducedMotion()) return;
      const grid = e.target?.closest?.(GRID_SELECTOR);
      if (currentGrid && currentGrid !== grid) {
        const prev = getState(currentGrid);
        prev.oT = 0;
        kick(currentGrid, prev);
      }
      currentGrid = grid || null;
      if (!grid) return;
      const s = getState(grid);
      const b = grid.getBoundingClientRect();
      s.x = e.clientX - b.left;
      s.y = e.clientY - b.top;
      s.oT = 1;
      apply(grid, s);
      kick(grid, s);
    }, { passive: true });

    document.addEventListener('pointerleave', () => {
      if (!currentGrid) return;
      const s = getState(currentGrid);
      s.oT = 0;
      kick(currentGrid, s);
      currentGrid = null;
    });
  }

  /* ── 10. LUCIAN #008 TACTILE COPY BUTTON CHECK MORPH ─────────────────── */
  function wireCopyFx() {
    function flash(btn, copiedLabel = 'Copied', defaultLabel = null) {
      if (!btn) return;
      const original = defaultLabel ?? btn.dataset.u008Label ?? btn.textContent;
      btn.dataset.u008Label = original;
      clearTimeout(btn._u008Timer);
      btn.classList.remove('is-copied');
      void btn.offsetWidth;
      btn.classList.add('is-copied');
      btn.textContent = copiedLabel;
      btn._u008Timer = setTimeout(() => {
        if (!btn.isConnected) return;
        btn.classList.remove('is-copied');
        btn.textContent = original;
      }, 1600);
    }
    window.EvoCopyFx = { flash };
  }

  /* ── 11. LUCIAN #450 SCROLL-PROGRESS BACK-TO-TOP BUTTON ──────────────── */
  function wireBackToTop() {
    const btn = $('back-to-top');
    if (!btn) return;

    function scrollTarget() {
      const content = qs('.analysis-content');
      if (content && content.scrollHeight > content.clientHeight + 40 && getComputedStyle(content).overflowY !== 'visible') {
        return content;
      }
      const main = $('main');
      if (main && main.scrollHeight > main.clientHeight + 40 && getComputedStyle(main).overflowY !== 'visible') {
        return main;
      }
      return document.scrollingElement || document.documentElement;
    }

    let raf = 0;
    function sync() {
      raf = 0;
      const el = scrollTarget();
      const top = el === document.scrollingElement || el === document.documentElement ? window.scrollY : el.scrollTop;
      const max = Math.max(1, el.scrollHeight - el.clientHeight);
      const ratio = Math.min(1, Math.max(0, top / max));
      btn.style.setProperty('--p', ratio.toFixed(3));
      const visible = top > 240;
      btn.classList.toggle('is-hide', !visible);
      btn.tabIndex = visible ? 0 : -1;
    }
    const schedule = () => { if (!raf) raf = requestAnimationFrame(sync); };

    window.addEventListener('scroll', schedule, { passive: true });
    qs('.analysis-content')?.addEventListener('scroll', schedule, { passive: true });
    $('main')?.addEventListener('scroll', schedule, { passive: true });
    window.addEventListener('resize', schedule, { passive: true });

    btn.addEventListener('click', () => {
      const el = scrollTarget();
      btn.classList.remove('is-fly');
      void btn.offsetWidth;
      btn.classList.add('is-fly');
      const behavior = reducedMotion() ? 'auto' : 'smooth';
      if (el === document.scrollingElement || el === document.documentElement) {
        window.scrollTo({ top: 0, behavior });
      } else {
        el.scrollTo({ top: 0, behavior });
      }
      setTimeout(() => btn.classList.remove('is-fly'), 720);
    });
    window.EvoBackToTop = { sync: schedule };
    sync();
  }

  /* ── 12. LUCIAN #440 WELCOME FOLDER MICRO-INTERACTION ────────────────── */
  function wireWelcomeEmptyState() {
    const welcome = $('welcome');
    if (!welcome) return;
    welcome.addEventListener('click', e => {
      if (!e.target?.closest?.('[data-action="connect"]')) return;
      welcome.classList.remove('is-done');
      void welcome.offsetWidth;
      welcome.classList.add('is-done');
      setTimeout(() => welcome.classList.remove('is-done'), 1200);
    });
  }

  /* ── 13. LUCIAN #002 MAGNETIC LIQUID-FLOOD CONNECT CTA ────────────────── */
  function wireMagneticConnect() {
    const clamp = (v, min, max) => Math.max(min, Math.min(max, v));
    document.addEventListener('pointermove', e => {
      if (e.pointerType === 'touch' || reducedMotion()) return;
      const btn = e.target?.closest?.('.u002-connect');
      qsa('.u002-connect').forEach(node => {
        if (node === btn) return;
        const lbl = node.querySelector('.u002-label');
        const orb = node.querySelector('.u002-orb');
        if (lbl) lbl.style.translate = '0px 0px';
        if (orb) orb.style.translate = '0px 0px';
      });
      if (!btn) return;
      const r = btn.getBoundingClientRect();
      const dx = e.clientX - (r.left + r.width / 2);
      const dy = e.clientY - (r.top + r.height / 2);
      const p = Math.max(0, 1 - Math.hypot(dx, dy) / 200);
      const lbl = btn.querySelector('.u002-label');
      const orb = btn.querySelector('.u002-orb');
      if (lbl) lbl.style.translate = clamp(dx * 0.04 * p, -3, 3).toFixed(1) + 'px ' + clamp(dy * 0.08 * p, -3, 3).toFixed(1) + 'px';
      if (orb) orb.style.translate = clamp(dx * 0.07 * p, -5, 5).toFixed(1) + 'px ' + clamp(dy * 0.12 * p, -5, 5).toFixed(1) + 'px';
    }, { passive: true });
    document.addEventListener('pointerleave', () => {
      qsa('.u002-connect').forEach(node => {
        const lbl = node.querySelector('.u002-label');
        const orb = node.querySelector('.u002-orb');
        if (lbl) lbl.style.translate = '0px 0px';
        if (orb) orb.style.translate = '0px 0px';
      });
    });
  }

  /* ── 14. LUCIAN #430 SINGLE-TRIGGER SEARCHABLE CONTEXT PICKER ────────── */
  const openPopovers = new Set();
  function closeAllPopovers(except) {
    openPopovers.forEach(closeFn => {
      if (closeFn !== except) closeFn(false);
    });
  }
  function keepOptionVisible(box, el) {
    if (!box || !el) return;
    const top = el.offsetTop;
    const bottom = top + el.offsetHeight;
    if (top < box.scrollTop) box.scrollTop = top;
    else if (bottom > box.scrollTop + box.clientHeight) box.scrollTop = bottom - box.clientHeight;
  }

  function inferContextMeta(opt) {
    const value = opt.value || '';
    const label = (opt.textContent || value).trim();
    const rawKind = (opt.dataset.kind || '').trim().toLowerCase();
    const rawBadge = (opt.dataset.badge || '').trim().toUpperCase();
    const sub = (opt.dataset.sub || '').trim();
    const lower = (label + ' ' + rawKind + ' ' + sub + ' ' + value).toLowerCase();

    let kind = rawKind;
    let badge = rawBadge;
    if (!kind) {
      if (lower.includes('whole repository') || lower.includes('snapshot') || value.startsWith('snapshot:')) kind = 'repository';
      else if (lower.includes('spring')) kind = 'spring';
      else if (lower.includes('typescript') || /\bts\b/.test(lower)) kind = 'typescript';
      else if (lower.includes('javascript') || /\bjs\b/.test(lower)) kind = 'javascript';
      else if (lower.includes('python') || /\bpy\b/.test(lower)) kind = 'python';
      else if (lower.includes('java') || lower.includes('maven') || lower.includes('gradle')) kind = 'java';
      else if (lower.includes('go ') || lower.includes('golang')) kind = 'go';
      else if (lower.includes('rust') || lower.includes('cargo')) kind = 'rust';
      else if (lower.includes('sql') || lower.includes('docker') || lower.includes('yaml') || lower.includes('config')) kind = 'config';
      else kind = 'context';
    }
    if (!badge) {
      const map = {
        repository: 'REPO',
        spring: 'SPRING',
        java: 'JAVA',
        typescript: 'TS',
        javascript: 'JS',
        python: 'PY',
        go: 'GO',
        rust: 'RS',
        config: 'CFG',
        kotlin: 'KT',
        csharp: 'C#',
        cpp: 'C++',
        polyglot: 'POLY'
      };
      badge = map[kind] || 'CTX';
    }
    let tone = 'default';
    if (kind === 'repository' || badge === 'REPO') tone = 'repo';
    else if (kind === 'spring' || badge === 'SPRING') tone = 'spring';
    else if (kind === 'java' || badge === 'JAVA' || badge === 'KT') tone = 'java';
    else if (kind === 'typescript' || kind === 'javascript' || badge === 'TS' || badge === 'JS') tone = 'ts';
    else if (kind === 'python' || kind === 'go' || badge === 'PY' || badge === 'GO') tone = 'py';
    else if (kind === 'config' || badge === 'CFG' || badge === 'SQL' || badge === 'OPS') tone = 'cfg';

    return { value, label, badge: badge.slice(0, 6), sub, kind, tone };
  }

  function wireContextPicker() {
    const select = $('context');
    const shell = $('context-picker-shell');
    const u430 = $('context-u430');
    const tr = $('context-picker-trigger');
    const who = tr?.querySelector('.u430-who');
    const pop = $('context-picker-pop');
    const input = $('context-picker-search');
    const lb = $('context-picker-listbox');
    const live = $('context-picker-live');
    // Remove legacy inline chip strip if still present in DOM
    $('context-quick-chips')?.remove();
    if (!select || !shell || !u430 || !tr || !who || !pop || !input || !lb) return;

    const uid = 'u430-ctx-';
    input.setAttribute('aria-controls', lb.id);

    let items = [];
    let filtered = [];
    let act = -1;
    let closeTimer = 0;
    let hydratedKey = '';
    let syncQueued = false;

    function triggerLazyLabel() {
      const key = items.map(i => i.value).join('|');
      if (!key || hydratedKey === key) return;
      hydratedKey = key;
      try {
        select.dispatchEvent(new Event('pointerenter'));
      } catch (_) {}
    }

    function readItems() {
      const all = [...select.options].filter(opt => opt.value !== '');
      return all.map(inferContextMeta);
    }

    function activeItem() {
      return items.find(it => it.value === select.value) || items[0] || null;
    }

    function renderTrigger(anim) {
      const cur = activeItem();
      const total = items.length;
      if (!total || !cur) {
        u430.hidden = true;
        return;
      }
      u430.hidden = false;
      who.textContent = '';

      const badge = document.createElement('span');
      badge.className = 'evo-ctx-badge';
      badge.dataset.tone = cur.tone;
      badge.setAttribute('aria-hidden', 'true');
      badge.textContent = cur.badge;

      const tx = document.createElement('span');
      tx.className = 'u430-tx';
      const b = document.createElement('b');
      b.textContent = cur.label;
      tx.appendChild(b);

      who.append(badge, tx);
      if (total > 1) {
        const cnt = document.createElement('span');
        cnt.className = 'u430-cnt';
        cnt.setAttribute('aria-hidden', 'true');
        cnt.textContent = String(total);
        who.appendChild(cnt);
      }

      tr.title = cur.sub ? `${cur.label} — ${cur.sub}` : cur.label;
      tr.setAttribute('aria-label', total > 1
        ? `Context: ${cur.label} (${total} available)`
        : `Context: ${cur.label}`);
      if (anim && !reducedMotion()) {
        tr.classList.remove('is-new');
        void tr.offsetWidth;
        tr.classList.add('is-new');
      }
    }

    function renderListbox(stagger) {
      const q = input.value.trim().toLowerCase();
      const curVal = activeItem()?.value || '';
      filtered = items.filter(it => {
        if (!q) return true;
        return it.label.toLowerCase().includes(q) ||
          it.badge.toLowerCase().includes(q) ||
          it.sub.toLowerCase().includes(q) ||
          it.kind.toLowerCase().includes(q);
      });
      lb.textContent = '';
      if (!filtered.length) {
        const no = document.createElement('div');
        no.className = 'u430-no';
        no.textContent = 'No matching contexts';
        lb.appendChild(no);
        act = -1;
        paintActive();
        return;
      }
      filtered.forEach((it, idx) => {
        const row = document.createElement('div');
        row.className = 'u430-o';
        row.setAttribute('role', 'option');
        row.id = uid + idx;
        row.dataset.index = String(idx);
        row.dataset.value = it.value;
        row.setAttribute('aria-selected', String(it.value === curVal));
        row.style.setProperty('--i', String(Math.min(idx, 8)));
        if (!stagger) row.style.animation = 'none';

        const av = document.createElement('span');
        av.className = 'u430-av';
        av.dataset.tone = it.tone;
        av.setAttribute('aria-hidden', 'true');
        av.textContent = it.badge;

        const body = document.createElement('span');
        body.className = 'u430-ob';
        const title = document.createElement('b');
        appendHighlighted(title, it.label, q);
        body.appendChild(title);
        if (it.sub) {
          const sub = document.createElement('small');
          appendHighlighted(sub, it.sub, q);
          body.appendChild(sub);
        }

        row.append(av, body);
        row.insertAdjacentHTML('beforeend', '<svg class="u430-ck" viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12.5 4.5 4.5L19 7.5"/></svg>');
        lb.appendChild(row);
      });
      const selPos = filtered.findIndex(it => it.value === curVal);
      act = q ? 0 : (selPos >= 0 ? selPos : 0);
      paintActive();
    }

    function paintActive() {
      const rows = qsa('.u430-o', lb);
      rows.forEach((o, i) => o.classList.toggle('is-act', i === act));
      const activeRow = rows[act];
      if (activeRow) {
        input.setAttribute('aria-activedescendant', activeRow.id);
        if (u430.classList.contains('is-open')) keepOptionVisible(lb, activeRow);
      } else {
        input.removeAttribute('aria-activedescendant');
      }
    }

    function positionPopover() {
      pop.classList.remove('is-left', 'is-up');
      const r = tr.getBoundingClientRect();
      const vw = window.innerWidth || 1200;
      const vh = window.innerHeight || 900;
      const popW = Math.min(390, vw - 24);
      if (r.left + popW > vw - 12 && r.right - popW >= 12) {
        pop.classList.add('is-left');
      }
      if (vh - r.bottom < 280 && r.top > 300) {
        pop.classList.add('is-up');
      }
    }

    function setOpen(open, focus) {
      const wasOpen = u430.classList.contains('is-open');
      if (open) {
        closeAllPopovers(closePopover);
        triggerLazyLabel();
        positionPopover();
      }
      u430.classList.toggle('is-open', open);
      tr.setAttribute('aria-expanded', String(open));
      input.setAttribute('aria-expanded', String(open));
      if (open) {
        openPopovers.add(closePopover);
        if (!wasOpen) {
          input.value = '';
          renderListbox(true);
        }
        if (focus) requestAnimationFrame(() => input.focus({ preventScroll: true }));
      } else {
        openPopovers.delete(closePopover);
        if (focus) tr.focus({ preventScroll: true });
      }
    }

    function closePopover(refocus) {
      if (u430.classList.contains('is-open')) setOpen(false, Boolean(refocus));
    }

    function commitSelection(value, fromPopover, refocusTrigger) {
      const changed = select.value !== value;
      if (fromPopover) {
        qsa('.u430-o', lb).forEach(o => o.setAttribute('aria-selected', String(o.dataset.value === value)));
      }
      if (changed) {
        select.value = value;
        select.dispatchEvent(new Event('change', { bubbles: true }));
      }
      const chosen = items.find(it => it.value === value);
      if (live && chosen) live.textContent = `Context: ${chosen.label}`;
      renderTrigger(true);
      if (fromPopover) {
        clearTimeout(closeTimer);
        closeTimer = setTimeout(() => {
          if (!u430.isConnected) return;
          setOpen(false, refocusTrigger);
        }, reducedMotion() ? 0 : 150);
      }
    }

    let lastSig = null;

    function sync() {
      syncQueued = false;
      items = readItems();
      if (!items.length) {
        if (!shell.hidden) shell.hidden = true;
        if (select.classList.contains('sr-only')) select.classList.remove('sr-only');
        if (select.hasAttribute('aria-hidden')) select.removeAttribute('aria-hidden');
        if (select.hasAttribute('tabindex')) select.removeAttribute('tabindex');
        lastSig = '';
        closePopover(false);
        return;
      }
      if (!select.classList.contains('sr-only')) select.classList.add('sr-only');
      if (select.getAttribute('aria-hidden') !== 'true') select.setAttribute('aria-hidden', 'true');
      if (select.tabIndex !== -1) select.tabIndex = -1;
      if (shell.hidden) shell.hidden = false;

      const sig = select.value + '||' + items.map(i => `${i.value}\t${i.label}\t${i.badge}\t${i.sub}\t${i.kind}`).join('\n');
      if (sig === lastSig) return;
      lastSig = sig;

      renderTrigger(false);
      if (u430.classList.contains('is-open')) {
        renderListbox(false);
      }
    }

    function scheduleSync() {
      if (syncQueued) return;
      syncQueued = true;
      requestAnimationFrame(sync);
    }

    tr.addEventListener('pointerenter', triggerLazyLabel);
    tr.addEventListener('click', e => {
      e.preventDefault();
      e.stopPropagation();
      setOpen(!u430.classList.contains('is-open'), true);
    });
    tr.addEventListener('keydown', e => {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        setOpen(true, true);
      }
    });
    input.addEventListener('input', () => renderListbox(false));
    input.addEventListener('keydown', e => {
      const rows = qsa('.u430-o', lb);
      const n = rows.length;
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        if (n) { act = (act + (e.key === 'ArrowDown' ? 1 : -1) + n) % n; paintActive(); }
      } else if (e.key === 'Home') {
        e.preventDefault();
        if (n) { act = 0; paintActive(); }
      } else if (e.key === 'End') {
        e.preventDefault();
        if (n) { act = n - 1; paintActive(); }
      } else if (e.key === 'Enter') {
        e.preventDefault();
        const o = rows[act];
        if (o) commitSelection(o.dataset.value, true, true);
      } else if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        setOpen(false, true);
      }
    });
    pop.addEventListener('mousedown', e => {
      if (e.target !== input) e.preventDefault();
    });
    lb.addEventListener('pointermove', e => {
      const o = e.target.closest?.('.u430-o');
      if (!o) return;
      const idx = Number(o.dataset.index);
      if (Number.isFinite(idx) && idx !== act) { act = idx; paintActive(); }
    });
    lb.addEventListener('click', e => {
      const o = e.target.closest?.('.u430-o');
      if (o) commitSelection(o.dataset.value, true, false);
    });
    document.addEventListener('pointerdown', e => {
      if (u430.classList.contains('is-open') && !u430.contains(e.target)) {
        setOpen(false, false);
      }
    });
    u430.addEventListener('focusout', e => {
      if (u430.classList.contains('is-open') && e.relatedTarget && !u430.contains(e.relatedTarget)) {
        setOpen(false, false);
      }
    });
    window.addEventListener('resize', () => {
      if (u430.classList.contains('is-open')) positionPopover();
    }, { passive: true });

    document.addEventListener('evo:contexts-updated', scheduleSync);
    select.addEventListener('change', scheduleSync);
    new MutationObserver(records => {
      const relevant = records.some(r => r.type !== 'attributes' || r.target !== select);
      if (relevant) scheduleSync();
    }).observe(select, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['data-badge', 'data-sub', 'data-kind', 'value'],
      characterData: true
    });

    window.EvoContextPicker = {
      sync,
      open: () => { if (!u430.hidden) setOpen(true, true); },
      close: () => setOpen(false, false),
      focus: () => { if (!u430.hidden) setOpen(true, true); else tr.focus(); }
    };

    sync();
  }

  /* ── 15. LUCIAN #031 CUSTOM SELECT LISTBOX ENHANCER ──────────────────── */
  function wireCustomSelects() {
    const enhanced = new Map();
    let seq = 0;

    function isEligible(select) {
      if (!select || select.tagName !== 'SELECT') return false;
      if (select.id === 'context') return false;
      if (select.multiple || select.size > 1) return false;
      if (select.hasAttribute('data-native-select')) return false;
      if (enhanced.has(select)) return false;
      return Boolean(select.closest('#repository-workspace, #workbench-tool'));
    }

    function enhanceSelect(select) {
      if (!isEligible(select)) return;
      const uid = 'u031-' + (++seq);
      const ariaLabel = select.getAttribute('aria-label') ||
        select.closest('label')?.querySelector('span')?.textContent?.trim() ||
        'Select option';

      const root = document.createElement('div');
      root.className = 'ux u031 evo-u031';
      root.dataset.forSelect = select.id || ariaLabel;

      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'u031-btn';
      btn.setAttribute('aria-haspopup', 'listbox');
      btn.setAttribute('aria-expanded', 'false');
      btn.setAttribute('aria-label', ariaLabel);
      btn.setAttribute('aria-controls', uid);
      btn.innerHTML =
        '<span class="u031-v"><b></b></span>' +
        '<svg class="u031-car" viewBox="0 0 18 18" aria-hidden="true"><path d="M4.5 7l4.5 4.5L13.5 7"/></svg>';

      const list = document.createElement('ul');
      list.className = 'u031-list';
      list.id = uid;
      list.setAttribute('role', 'listbox');
      list.tabIndex = -1;
      list.setAttribute('aria-label', ariaLabel);

      const hl = document.createElement('li');
      hl.className = 'u031-hl';
      hl.setAttribute('role', 'presentation');
      hl.setAttribute('aria-hidden', 'true');
      list.appendChild(hl);

      root.append(btn, list);

      select.classList.add('sr-only');
      select.setAttribute('aria-hidden', 'true');
      select.tabIndex = -1;
      select.insertAdjacentElement('afterend', root);

      let optEls = [];
      let act = 0;
      let typed = '';
      let typeTimer = 0;

      function readOpts() {
        return [...select.options].map((o, idx) => ({
          index: idx,
          value: o.value,
          label: (o.textContent || o.value).trim(),
          disabled: o.disabled
        }));
      }

      function mark() {
        if (!optEls.length) {
          hl.style.opacity = '0';
          list.removeAttribute('aria-activedescendant');
          return;
        }
        act = Math.max(0, Math.min(optEls.length - 1, act));
        hl.style.opacity = '1';
        hl.style.setProperty('--i', String(act));
        list.setAttribute('aria-activedescendant', optEls[act].id);
        if (list.classList.contains('is-open')) keepOptionVisible(list, optEls[act]);
      }

      function syncUI(animateSwap) {
        root.hidden = select.hidden;
        btn.disabled = select.disabled;
        const opts = readOpts();
        const selIdx = Math.max(0, select.selectedIndex >= 0 ? select.selectedIndex : opts.findIndex(o => o.value === select.value));
        const cur = opts[selIdx] || opts[0];
        const bEl = btn.querySelector('.u031-v b');
        const nextText = cur ? cur.label : '—';
        const changedText = bEl.textContent && bEl.textContent !== nextText;
        bEl.textContent = nextText;
        btn.title = nextText;
        if (animateSwap && changedText && !reducedMotion()) {
          const v = btn.querySelector('.u031-v');
          if (v?.animate) {
            v.animate([{ transform: 'translateY(6px)', opacity: 0 }, { transform: 'none', opacity: 1 }], {
              duration: 320,
              easing: 'cubic-bezier(.34,1.45,.64,1)'
            });
          }
        }

        // Rebuild option rows
        while (list.children.length > 1) list.lastElementChild.remove();
        optEls = opts.map((o, i) => {
          const li = document.createElement('li');
          li.className = 'u031-o';
          li.id = `${uid}-${i}`;
          li.setAttribute('role', 'option');
          li.setAttribute('aria-selected', String(i === selIdx));
          if (o.disabled) li.setAttribute('aria-disabled', 'true');
          const txt = document.createElement('span');
          txt.className = 'u031-ot';
          txt.textContent = o.label;
          li.appendChild(txt);
          li.insertAdjacentHTML('beforeend', '<svg class="u031-ck" viewBox="0 0 18 18" aria-hidden="true"><path d="M4 9.5l3.5 3.5L14 6"/></svg>');
          li.addEventListener('pointermove', () => { if (act !== i) { act = i; mark(); } });
          li.addEventListener('mousedown', e => { e.preventDefault(); e.stopPropagation(); });
          li.addEventListener('click', e => {
            e.preventDefault();
            e.stopPropagation();
            if (!o.disabled) choose(i);
          });
          list.appendChild(li);
          return li;
        });
        act = selIdx >= 0 ? selIdx : 0;
        mark();
      }

      function positionList() {
        list.classList.remove('is-up', 'is-right');
        const r = btn.getBoundingClientRect();
        const vh = window.innerHeight || 900;
        const vw = window.innerWidth || 1200;
        if (vh - r.bottom < 220 && r.top > 240) {
          list.classList.add('is-up');
        }
        if (r.left + Math.max(r.width, 240) > vw - 12) {
          list.classList.add('is-right');
        }
      }

      function open() {
        if (select.disabled || !optEls.length) return;
        closeAllPopovers(close);
        positionList();
        root.classList.add('is-open');
        btn.setAttribute('aria-expanded', 'true');
        list.classList.add('is-open');
        openPopovers.add(close);
        const selIdx = Math.max(0, select.selectedIndex);
        act = selIdx;
        mark();
        list.focus({ preventScroll: true });
      }

      function close(refocus) {
        if (!root.classList.contains('is-open')) return;
        root.classList.remove('is-open');
        btn.setAttribute('aria-expanded', 'false');
        list.classList.remove('is-open');
        openPopovers.delete(close);
        if (refocus) btn.focus({ preventScroll: true });
      }

      function choose(i) {
        const opt = select.options[i];
        if (!opt || opt.disabled) return;
        const changed = select.selectedIndex !== i;
        select.selectedIndex = i;
        select.value = opt.value;
        syncUI(true);
        close(true);
        if (changed) {
          select.dispatchEvent(new Event('input', { bubbles: true }));
          select.dispatchEvent(new Event('change', { bubbles: true }));
        }
      }

      btn.addEventListener('mousedown', e => {
        // Prevent enclosing <label> from stealing focus to the hidden <select>
        e.stopPropagation();
      });
      btn.addEventListener('click', e => {
        e.preventDefault();
        e.stopPropagation();
        if (list.classList.contains('is-open')) close(true);
        else open();
      });
      btn.addEventListener('keydown', e => {
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          open();
        }
      });
      list.addEventListener('keydown', e => {
        if (e.key === 'ArrowDown') { e.preventDefault(); act = Math.min(optEls.length - 1, act + 1); mark(); }
        else if (e.key === 'ArrowUp') { e.preventDefault(); act = Math.max(0, act - 1); mark(); }
        else if (e.key === 'Home') { e.preventDefault(); act = 0; mark(); }
        else if (e.key === 'End') { e.preventDefault(); act = optEls.length - 1; mark(); }
        else if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(act); }
        else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(true); }
        else if (e.key === 'Tab') { close(false); }
        else if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
          typed += e.key.toLowerCase();
          clearTimeout(typeTimer);
          typeTimer = setTimeout(() => { typed = ''; }, 600);
          const hit = [...select.options].findIndex(o => (o.textContent || '').trim().toLowerCase().startsWith(typed));
          if (hit >= 0) { act = hit; mark(); }
        }
      });
      list.addEventListener('blur', () => {
        setTimeout(() => {
          if (root.isConnected && !root.contains(document.activeElement)) close(false);
        }, 0);
      });

      const onChange = () => syncUI(false);
      select.addEventListener('change', onChange);
      const mo = new MutationObserver(() => syncUI(false));
      mo.observe(select, { childList: true, subtree: true, attributes: true, attributeFilter: ['disabled', 'hidden'], characterData: true });

      const entry = { select, root, mo, onChange, close, sync: () => syncUI(false) };
      enhanced.set(select, entry);
      syncUI(false);
    }

    function scan(node = document) {
      // Prune detached selects
      for (const [sel, entry] of enhanced.entries()) {
        if (!sel.isConnected) {
          entry.mo.disconnect();
          entry.select.removeEventListener('change', entry.onChange);
          openPopovers.delete(entry.close);
          if (entry.root.isConnected) entry.root.remove();
          enhanced.delete(sel);
        }
      }
      const rootNode = node.nodeType === 1 ? node : document;
      if (rootNode.tagName === 'SELECT') {
        enhanceSelect(rootNode);
        return;
      }
      qsa('select', rootNode).forEach(enhanceSelect);
    }

    document.addEventListener('pointerdown', e => {
      for (const entry of enhanced.values()) {
        if (entry.root.classList.contains('is-open') && !entry.root.contains(e.target)) {
          entry.close(false);
        }
      }
    });
    document.addEventListener('evo:contexts-updated', () => {
      enhanced.forEach(entry => entry.sync());
    });

    const main = $('main') || document.body;
    let scanTimer = 0;
    new MutationObserver(records => {
      if (scanTimer) return;
      const relevant = records.some(r => {
        const t = r.target;
        return t?.nodeType === 1 && !t.closest?.('.evo-u031, #context-picker-shell');
      });
      if (!relevant) return;
      scanTimer = requestAnimationFrame(() => {
        scanTimer = 0;
        scan(main);
      });
    }).observe(main, { childList: true, subtree: true });

    scan(main);
  }

  /* ── 16. LUCIAN #027 SEARCH BAR POLISH & CLEAR BUTTON ────────────────── */
  function wireSearchBar() {
    const form = $('search-form');
    const input = $('search');
    const clearBtn = $('search-clear');
    if (!form || !input || !clearBtn) return;

    function sync() {
      const hasVal = input.value.length > 0;
      form.classList.toggle('has-value', hasVal);
      clearBtn.tabIndex = hasVal ? 0 : -1;
    }

    input.addEventListener('input', sync);
    input.addEventListener('change', sync);
    input.addEventListener('focus', sync);
    form.addEventListener('submit', () => requestAnimationFrame(sync));
    clearBtn.addEventListener('click', () => {
      input.value = '';
      sync();
      input.dispatchEvent(new Event('input', { bubbles: true }));
      if (typeof form.requestSubmit === 'function') form.requestSubmit();
      else form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      input.focus();
    });
    // Sync when tab/repo resets input programmatically
    document.addEventListener('evo:contexts-updated', sync);
    sync();
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
    wireThemeReveal();
    wireSpotlightCards();
    wireCopyFx();
    wireBackToTop();
    wireWelcomeEmptyState();
    wireMagneticConnect();
    wireContextPicker();
    wireCustomSelects();
    wireSearchBar();
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
