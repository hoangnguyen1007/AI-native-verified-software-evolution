(() => {
  'use strict';

  const $ = id => document.getElementById(id);
  const qs = (sel, ctx = document) => ctx.querySelector(sel);
  const qsa = (sel, ctx = document) => [...ctx.querySelectorAll(sel)];

  /* ── 8. KEYBOARD SHORTCUTS ───────────────────────────────────────────── */
  function wireExtraShortcuts() {
    document.addEventListener('keydown', e => {
      if ($('repository-workspace')?.hidden) return;
      const tag = document.activeElement?.tagName;
      const inInput = ['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) ||
                      document.activeElement?.isContentEditable;

      if (qs('dialog[open]')) return;
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
          qs('.graph-fullscreen-button', graphPanel)?.click();
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

      const map = { g: 'tab-graph', o: 'tab-overview', r: 'tab-policy', m: 'view-metrics', s: 'view-structure' };
      if (map[e.key]) { e.preventDefault(); $(map[e.key])?.click(); }
    });
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

  /* ── 18. SHORTCUT OVERLAY & COMMAND PALETTE ──────────────────────────── */
  function wireCommandPalette() {
    const dialog = $('command-palette');
    const input = $('command-palette-input');
    const list = $('command-palette-list');
    const trigger = $('command-palette-trigger');
    const closeBtn = $('command-palette-close');
    if (!dialog || !input || !list) return;

    let items = [];
    let selectedIndex = 0;

    function baseCommands() {
      const wb = window.EvoWorkbench;
      const app = window.EvoApp;
      const go = tab => app?.loadTab?.(tab);
      const cmds = [
        { category: 'Navigate', label: 'Overview & Coverage', hint: 'O', run: () => go('overview') },
        { category: 'Navigate', label: 'Structure Explorer', hint: 'S', run: () => go('structure') },
        { category: 'Navigate', label: 'Architecture Graph', hint: 'G', run: () => go('graph') },
        { category: 'Navigate', label: 'Connections & Bridges', hint: 'Bridges', run: () => go('connections') },
        { category: 'Navigate', label: 'Metrics & Hotspots', hint: 'M', run: () => go('metrics') },
        { category: 'Navigate', label: 'Insights & Tangles', hint: 'Insights', run: () => go('insights') },
        { category: 'Navigate', label: 'Policy & Repair', hint: 'R', run: () => go('policy') },
        { category: 'Navigate', label: 'AI Architecture Studio', hint: 'Chat', run: () => go('architecture-chat') },
        { category: 'Action', label: 'Auto-detect Policy Rules', hint: 'Policy', run: () => {
          go('overview')?.then?.(() => setTimeout(() => qs('.auto-policy-button')?.click(), 80));
        }},
        { category: 'Action', label: 'Ask AI to Explain Coupling & Hotspots', hint: 'AI Agent', run: () => {
          if (wb?.askAiPrompt) wb.askAiPrompt('Inspect top architectural hotspots, cyclic dependencies, and coupling risks across this snapshot and suggest safe refactoring steps.');
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
          label: sym.label || sym.identity,
          hint: sym.group || 'Inspect',
          run: () => window.EvoWorkbench?.focusSymbol?.(sym)
        }))
      ];
      selectedIndex = 0;
      renderList();
    }

    function renderList() {
      list.textContent = '';
      if (!items.length) {
        const empty = document.createElement('div');
        empty.className = 'command-palette-empty';
        empty.textContent = 'No matching commands or symbols.';
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
        lbl.textContent = item.label;
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

    function runItem(item) {
      if (!item) return;
      if (dialog.open) dialog.close();
      item.run();
    }

    function openPalette() {
      if (dialog.open) return;
      input.value = '';
      if (typeof dialog.showModal === 'function') dialog.showModal();
      else dialog.setAttribute('open', '');
      refreshList();
      input.focus();
    }

    window.EvoCommandPalette = { open: openPalette };
    trigger?.addEventListener('click', openPalette);
    closeBtn?.addEventListener('click', () => dialog.close?.());
    dialog.addEventListener('click', e => { if (e.target === dialog) dialog.close?.(); });
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
        dialog.close?.();
      }
    });
    document.addEventListener('keydown', e => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        if (dialog.open) dialog.close?.();
        else openPalette();
      }
    });
  }

  function wireShortcutOverlay() {
    const overlay = $('shortcut-overlay');
    const closeBtn = $('shortcut-overlay-close') || $('shortcut-close');
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
      ($('shortcut-overlay-close') || $('shortcut-close'))?.focus();
    } else {
      overlay.setAttribute('hidden', '');
    }
  }

  /* ── Init ────────────────────────────────────────────────────────────── */
  function init() {
    wireExtraShortcuts();
    wireCommandPalette();
    wireRepairHighlight();
    wireTabScrollReset();
    wireShortcutOverlay();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
