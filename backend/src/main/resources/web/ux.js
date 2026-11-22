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

  /* ── Init ────────────────────────────────────────────────────────────── */
  function init() {
    wireExtraShortcuts();
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
