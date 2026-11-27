/* Layer-3 Interactive Levelized DSM Matrix (pure DOM, 0 CDN, strict CSP, 320px–1440px).
 * Consumes POST /api/v1/graphs/{graph}/queries/dsm and syncs with Dependency Map & Chat via EvoBus.
 */
(() => {
  'use strict';

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

  function normalizeDsm(raw) {
    const order = Array.isArray(raw?.order) ? raw.order.map((item, i) => ({
      index: Number.isFinite(Number(item.index)) ? Number(item.index) : i,
      id: String(item.id || ''),
      label: String(item.label || item.id || ('Item ' + (i + 1))),
      kind: String(item.kind || ''),
      communityId: item.communityId ?? null,
      inDegree: Number(item.inDegree || 0),
      outDegree: Number(item.outDegree || 0),
      sccIndex: item.sccIndex ?? null,
      trustTier: item.trustTier || null
    })) : [];

    const cells = Array.isArray(raw?.cells) ? raw.cells.map(c => {
      const r = Number(c.r ?? c.row ?? -1);
      const col = Number(c.c ?? c.col ?? -1);
      return {
        r,
        c: col,
        weight: Math.max(1, Number(c.weight ?? c.count ?? 1)),
        isCycle: Boolean(c.isCycle || c.inCycle || (col > r && r >= 0)),
        isBackEdge: col > r && r >= 0,
        isViolation: Boolean(c.isViolation),
        trustTier: c.trustTier || null
      };
    }).filter(c => c.r >= 0 && c.c >= 0 && c.r < order.length && c.c < order.length) : [];

    const sccBlocks = Array.isArray(raw?.sccBlocks) ? raw.sccBlocks.map((b, i) => ({
      index: Number.isFinite(Number(b.index)) ? Number(b.index) : i,
      start: Number(b.start ?? 0),
      end: Number(b.end ?? 0),
      size: Number(b.size ?? ((Number(b.end ?? 0) - Number(b.start ?? 0)) + 1)),
      cyclic: Boolean(b.cyclic),
      members: Array.isArray(b.members) ? b.members : []
    })) : [];

    const scoreNum = raw?.levelizationScore != null && Number.isFinite(Number(raw.levelizationScore))
      ? Number(raw.levelizationScore)
      : null;

    return {
      graphDigest: raw?.graphDigest || '',
      context: raw?.context || '',
      granularity: String(raw?.granularity || 'PACKAGE').toUpperCase(),
      levelizationScore: scoreNum,
      totalItems: Number(raw?.totalItems ?? order.length),
      truncated: Boolean(raw?.truncated),
      order,
      sccBlocks,
      cells
    };
  }

  /**
   * Pure summary helper (tested in workbench-model.test.cjs).
   */
  function summarizeDsm(raw) {
    const d = normalizeDsm(raw);
    const cyclicBlocks = d.sccBlocks.filter(b => b.cyclic && b.size > 1);
    const backEdges = d.cells.filter(c => c.isBackEdge || c.isCycle);
    const violations = d.cells.filter(c => c.isViolation);
    const pct = d.levelizationScore == null ? null : Math.round(d.levelizationScore * 1000) / 10;
    const tone = pct == null ? 'muted' : pct >= 95 ? 'good' : pct >= 75 ? 'warn' : 'bad';
    return {
      itemCount: d.order.length,
      totalItems: d.totalItems,
      truncated: d.truncated,
      cellCount: d.cells.length,
      cyclicBlockCount: cyclicBlocks.length,
      backEdgeCount: backEdges.length,
      violationCount: violations.length,
      levelizationPct: pct,
      levelizationText: pct == null ? 'Unavailable' : pct.toFixed(1).replace(/\.0$/, '') + '%',
      tone
    };
  }

  function shortDisplayLabel(label, maxLen = 26) {
    const s = String(label || '');
    if (s.length <= maxLen) return s;
    const parts = s.split(/[./]/);
    const tail = parts.length > 2 ? '…' + parts.slice(-2).join('.') : s;
    return tail.length <= maxLen ? tail : '…' + tail.slice(-(maxLen - 1));
  }

  async function renderView(env, host) {
    const { api, safe, btn, card, empty, defaultDependencyContext, download } = host;

    const root = card(
      'Design Structure Matrix (DSM)',
      'Topological condensation ordering places layered dependencies in the lower triangle and exposes cyclic back-edges in the upper triangle.'
    );
    root.classList.add('evo-dsm-view');

    const selectedContext = defaultDependencyContext ? defaultDependencyContext(env) : env.context;
    let granularity = 'PACKAGE';
    let onlyIssues = false;
    let maxItems = 80;
    let version = 0;
    let currentData = null;
    let redrawCurrent = null;

    // Granularity switcher (Module | Package | Type)
    const granGroup = el('div', 'evo-graph-seg-group');
    granGroup.setAttribute('role', 'group');
    granGroup.setAttribute('aria-label', 'DSM granularity');
    granGroup.append(el('span', 'evo-graph-seg-label', 'Granularity'));
    const granButtons = new Map();
    for (const [gVal, gLabel] of [['MODULE', 'Module'], ['PACKAGE', 'Package'], ['TYPE', 'Type']]) {
      const b = btn(gLabel, () => {
        granularity = gVal;
        syncGranButtons();
        return loadDsm(false);
      }, 'evo-graph-seg-btn' + (granularity === gVal ? ' is-active' : ''));
      b.dataset.granularity = gVal;
      b.setAttribute('aria-pressed', String(granularity === gVal));
      granButtons.set(gVal, b);
      granGroup.append(b);
    }

    function syncGranButtons() {
      for (const [gVal, b] of granButtons) {
        const on = gVal === granularity;
        b.classList.toggle('is-active', on);
        b.setAttribute('aria-pressed', String(on));
      }
    }

    const filterGroup = el('div', 'evo-graph-seg-group');
    filterGroup.setAttribute('role', 'group');
    filterGroup.setAttribute('aria-label', 'DSM cell filter');
    const allCellsBtn = btn('All edges', () => {
      onlyIssues = false;
      allCellsBtn.classList.add('is-active');
      issuesBtn.classList.remove('is-active');
      redrawCurrent?.();
    }, 'evo-graph-seg-btn is-active');
    const issuesBtn = btn('Cycles & violations', () => {
      onlyIssues = true;
      issuesBtn.classList.add('is-active');
      allCellsBtn.classList.remove('is-active');
      redrawCurrent?.();
    }, 'evo-graph-seg-btn');
    filterGroup.append(allCellsBtn, issuesBtn);

    const kpiHost = el('div', 'evo-dsm-kpis');
    const toolbar = append(el('div', 'evo-dsm-toolbar'), granGroup, filterGroup, kpiHost);
    const body = el('div', 'evo-dsm-body');
    root.append(toolbar, body);

    const themeObs = typeof MutationObserver === 'function'
      ? new MutationObserver(() => redrawCurrent?.())
      : null;
    themeObs?.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    env.signal.addEventListener('abort', () => themeObs?.disconnect(), { once: true });

    function readColors() {
      const isLight = document.documentElement.dataset.theme === 'light';
      return {
        isLight,
        bg: isLight ? '#f6f7f2' : '#0b1110',
        surface: isLight ? '#ffffff' : '#111917',
        gridLine: isLight ? '#d8e0d6' : 'rgba(214, 236, 226, 0.10)',
        diagBg: isLight ? '#eef2eb' : '#182320',
        upperTint: isLight ? 'rgba(220, 38, 38, 0.035)' : 'rgba(248, 113, 113, 0.045)',
        hoverBand: isLight ? 'rgba(20, 108, 78, 0.09)' : 'rgba(140, 226, 185, 0.10)',
        text: isLight ? '#163035' : '#e4efe9',
        muted: isLight ? '#465a52' : '#839a92',
        lowerRgb: isLight ? [20, 108, 78] : [79, 209, 154],
        cycleRgb: isLight ? [185, 28, 28] : [248, 113, 113],
        violationRgb: isLight ? [180, 83, 9] : [251, 191, 36],
        sccStroke: isLight ? '#dc2626' : '#f87171',
        selectStroke: isLight ? '#146c4e' : '#8ce2b9'
      };
    }

    function renderDsmMatrix(raw) {
      const data = normalizeDsm(raw);
      currentData = data;
      const summary = summarizeDsm(data);

      kpiHost.replaceChildren(
        append(
          el('span', 'evo-dsm-kpi-pill is-' + summary.tone),
          el('span', '', 'Levelization'),
          el('strong', '', summary.levelizationText)
        ),
        el('span', 'evo-dsm-kpi-pill', `${summary.itemCount} items · ${summary.cellCount} cells`),
        summary.cyclicBlockCount > 0
          ? el('span', 'evo-dsm-kpi-pill is-bad', `${summary.cyclicBlockCount} cyclic SCC block${summary.cyclicBlockCount === 1 ? '' : 's'}`)
          : el('span', 'evo-dsm-kpi-pill is-good', 'Acyclic (0 SCC cycles)'),
        summary.truncated
          ? el('span', 'evo-dsm-kpi-pill is-warn', `Truncated (${summary.itemCount} of ${summary.totalItems})`)
          : null,
        btn('Download JSON', () => download(raw, `dsm-${data.granularity.toLowerCase()}.json`), 'button subtle')
      );

      if (!data.order.length) {
        body.replaceChildren(empty(
          `No ${data.granularity.toLowerCase()} dependencies in this context`,
          'Try switching granularity between Module, Package, and Type, or select a code analysis context above.'
        ));
        return;
      }

      const n = data.order.length;
      const cellMap = new Map();
      let maxWeight = 1;
      for (const cell of data.cells) {
        cellMap.set(cell.r + ':' + cell.c, cell);
        if (cell.weight > maxWeight) maxWeight = cell.weight;
      }

      const cellSize = n <= 16 ? 28 : n <= 35 ? 22 : n <= 60 ? 18 : 15;
      const labelW = n <= 35 ? 210 : 176;
      const headerH = 38;
      const pad = 12;
      const cssW = labelW + n * cellSize + pad * 2;
      const cssH = headerH + n * cellSize + pad * 2;

      const stage = el('div', 'evo-dsm-stage');
      const legend = append(
        el('div', 'evo-dsm-legend'),
        append(el('span', 'evo-dsm-legend-item'), el('i', 'evo-dsm-swatch is-lower'), el('span', '', 'Lower triangle: layered dependency')),
        append(el('span', 'evo-dsm-legend-item'), el('i', 'evo-dsm-swatch is-cycle'), el('span', '', 'Upper triangle / Cycle back-edge')),
        append(el('span', 'evo-dsm-legend-item'), el('i', 'evo-dsm-swatch is-violation'), el('span', '', 'Policy violation')),
        append(el('span', 'evo-dsm-legend-item'), el('i', 'evo-dsm-swatch is-scc'), el('span', '', 'SCC cycle block'))
      );

      const scrollWrap = el('div', 'evo-dsm-scroll-wrap');
      scrollWrap.tabIndex = 0;
      scrollWrap.setAttribute('role', 'region');
      scrollWrap.setAttribute('aria-label', `${data.granularity} dependency structure matrix`);

      const canvas = document.createElement('canvas');
      canvas.className = 'evo-dsm-canvas';
      canvas.style.width = cssW + 'px';
      canvas.style.height = cssH + 'px';
      scrollWrap.append(canvas);

      const detailBar = el('div', 'evo-dsm-detail-bar');
      const detailCopy = el('div', 'evo-dsm-detail-copy', 'Hover or click any matrix cell to inspect dependency weight, trust tier, and jump to the Dependency Map or AI Chat.');
      detailCopy.setAttribute('role', 'status');
      const detailActions = el('div', 'evo-dsm-detail-actions');
      detailBar.append(detailCopy, detailActions);

      stage.append(legend, scrollWrap, detailBar);

      let hoverR = -1, hoverC = -1;
      let selectedCell = null;

      function updateDetailBar(r, c, pinned) {
        const src = data.order[r];
        const dst = data.order[c];
        if (!src || !dst) return;
        const cell = cellMap.get(r + ':' + c) || null;
        detailCopy.replaceChildren();
        detailActions.replaceChildren();

        if (r === c) {
          detailCopy.append(
            el('strong', '', `#${r + 1} ${src.label}`),
            el('span', '', `${src.inDegree} in · ${src.outDegree} out`),
            window.EvoTrust ? window.EvoTrust.renderBadge(window.EvoTrust.inferFromRecord(src), { compact: true }) : null
          );
          if (pinned) {
            detailActions.append(
              btn('Focus on Dependency Map', () => {
                window.EvoBus?.emit('evo:canvas-focus', {
                  nodeIds: [src.id],
                  contextId: selectedContext,
                  highlightReason: 'dsm-node'
                });
              }, 'button subtle'),
              btn('Ask AI about component', () => {
                window.EvoBus?.emit('evo:chat-seed', {
                  prompt: `Analyze the dependencies, coupling, and levelization role of \`${src.label}\` (${src.inDegree} incoming, ${src.outDegree} outgoing).`,
                  focusNodeId: src.id,
                  contextId: selectedContext,
                  mode: 'EXPLORE'
                });
              }, 'button subtle')
            );
          }
          return;
        }

        if (!cell) {
          detailCopy.append(
            el('strong', '', `${src.label} → ${dst.label}`),
            el('span', '', 'No direct dependency recorded.')
          );
          return;
        }

        const tier = window.EvoTrust
          ? window.EvoTrust.inferFromRecord({ trustTier: cell.trustTier || src.trustTier, kind: src.kind })
          : (cell.trustTier || 'COMPILER');
        const statusTags = [];
        if (cell.isCycle || cell.isBackEdge) statusTags.push('Cycle / back-edge');
        if (cell.isViolation) statusTags.push('Policy violation');
        if (!statusTags.length) statusTags.push('Layered dependency');

        detailCopy.append(
          el('strong', '', `${src.label} → ${dst.label}`),
          el('span', '', `Weight ${cell.weight} · ${statusTags.join(' · ')}`),
          window.EvoTrust ? window.EvoTrust.renderBadge(tier, { compact: true }) : null
        );

        const isCyclic = Boolean(cell.isCycle || cell.isBackEdge);
        detailActions.append(
          btn('Focus on Dependency Map', () => {
            window.EvoBus?.emit('evo:canvas-focus', {
              nodeIds: [src.id, dst.id],
              contextId: selectedContext,
              highlightReason: isCyclic ? 'dsm-cycle-edge' : 'dsm-edge'
            });
          }, 'button subtle'),
          btn(isCyclic ? 'Ask AI about cycle' : 'Ask AI about dependency', () => {
            const promptText = isCyclic
              ? `Explain the cyclic back-edge \`${src.label}\` → \`${dst.label}\` (weight ${cell.weight}) in the ${data.granularity} DSM and propose the smallest safe refactoring to break this cycle.`
              : `Explain why \`${src.label}\` depends on \`${dst.label}\` (weight ${cell.weight}) and whether this coupling should be decoupled.`;
            window.EvoBus?.emit('evo:chat-seed', {
              prompt: promptText,
              focusNodeId: src.id,
              contextId: selectedContext,
              mode: isCyclic || cell.isViolation ? 'REPAIR' : 'EXPLORE'
            });
          }, 'button primary')
        );
        if (isCyclic || cell.isViolation) {
          detailActions.append(
            btn('Seed repair', () => {
              window.EvoBus?.emit('evo:repair-seed', {
                title: `Break ${data.granularity.toLowerCase()} back-edge: ${shortDisplayLabel(src.label, 32)} → ${shortDisplayLabel(dst.label, 32)}`,
                description: `DSM back-edge with weight ${cell.weight} between ${src.label} and ${dst.label}.`,
                targetSymbols: [src.id, dst.id],
                targetPaths: []
              });
            }, 'button subtle')
          );
        }
      }

      function draw() {
        const dpr = Math.min(2, window.devicePixelRatio || 1);
        canvas.width = Math.round(cssW * dpr);
        canvas.height = Math.round(cssH * dpr);
        const ctx = canvas.getContext('2d');
        if (!ctx) return;
        const c = readColors();
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.fillStyle = c.bg;
        ctx.fillRect(0, 0, cssW, cssH);

        const ox = pad + labelW;
        const oy = pad + headerH;
        const matSize = n * cellSize;

        // Base matrix surface
        ctx.fillStyle = c.surface;
        ctx.fillRect(ox, oy, matSize, matSize);

        // Upper-triangle subtle warning tint
        ctx.fillStyle = c.upperTint;
        ctx.beginPath();
        ctx.moveTo(ox, oy);
        ctx.lineTo(ox + matSize, oy);
        ctx.lineTo(ox + matSize, oy + matSize);
        ctx.closePath();
        ctx.fill();

        // Diagonal cells
        ctx.fillStyle = c.diagBg;
        for (let i = 0; i < n; i++) {
          ctx.fillRect(ox + i * cellSize, oy + i * cellSize, cellSize, cellSize);
        }

        // Hover crosshair band
        if (hoverR >= 0 && hoverR < n) {
          ctx.fillStyle = c.hoverBand;
          ctx.fillRect(pad, oy + hoverR * cellSize, labelW + matSize, cellSize);
        }
        if (hoverC >= 0 && hoverC < n) {
          ctx.fillStyle = c.hoverBand;
          ctx.fillRect(ox + hoverC * cellSize, pad, cellSize, headerH + matSize);
        }

        // Grid lines
        ctx.strokeStyle = c.gridLine;
        ctx.lineWidth = 1;
        ctx.beginPath();
        for (let i = 0; i <= n; i++) {
          const x = ox + i * cellSize + 0.5;
          const y = oy + i * cellSize + 0.5;
          ctx.moveTo(x, oy);
          ctx.lineTo(x, oy + matSize);
          ctx.moveTo(ox, y);
          ctx.lineTo(ox + matSize, y);
        }
        ctx.stroke();

        // Column index headers (1..N) & Row labels
        ctx.font = `600 ${Math.min(11, Math.max(9, cellSize - 6))}px "JetBrains Mono", ui-monospace, monospace`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        for (let i = 0; i < n; i++) {
          ctx.fillStyle = i === hoverC ? c.selectStroke : c.muted;
          ctx.fillText(String(i + 1), ox + i * cellSize + cellSize / 2, oy - 12);
        }

        ctx.font = `500 ${Math.min(11.5, Math.max(9.5, cellSize - 5))}px "Source Sans 3", system-ui, sans-serif`;
        ctx.textAlign = 'right';
        ctx.textBaseline = 'middle';
        for (let i = 0; i < n; i++) {
          const item = data.order[i];
          ctx.fillStyle = i === hoverR ? c.selectStroke : c.text;
          const prefix = String(i + 1).padStart(2, '0') + '  ';
          ctx.fillText(prefix + shortDisplayLabel(item.label, n <= 35 ? 24 : 19), ox - 8, oy + i * cellSize + cellSize / 2);
        }

        // Data cells
        const logDenom = Math.log2(maxWeight + 1);
        for (const cell of data.cells) {
          const isCyclic = cell.isCycle || cell.isBackEdge;
          if (onlyIssues && !isCyclic && !cell.isViolation) continue;
          const intensity = 0.34 + 0.62 * (Math.log2(cell.weight + 1) / Math.max(1, logDenom));
          const rgb = cell.isViolation ? c.violationRgb : (isCyclic ? c.cycleRgb : c.lowerRgb);
          const x = ox + cell.c * cellSize + 1.5;
          const y = oy + cell.r * cellSize + 1.5;
          const sz = Math.max(2, cellSize - 3);
          ctx.fillStyle = `rgba(${rgb[0]},${rgb[1]},${rgb[2]},${intensity.toFixed(2)})`;
          ctx.fillRect(x, y, sz, sz);

          if (cellSize >= 18) {
            ctx.fillStyle = c.isLight ? '#0f172a' : '#f8fafc';
            ctx.font = `700 ${Math.min(10, cellSize - 9)}px "JetBrains Mono", ui-monospace, monospace`;
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(cell.weight > 99 ? '99+' : String(cell.weight), x + sz / 2, y + sz / 2 + 0.5);
          }
        }

        // SCC diagonal cycle-block frames
        for (const block of data.sccBlocks) {
          if (!block.cyclic || block.size <= 1) continue;
          const bx = ox + block.start * cellSize;
          const by = oy + block.start * cellSize;
          const bw = block.size * cellSize;
          ctx.save();
          ctx.strokeStyle = c.sccStroke;
          ctx.lineWidth = 2;
          ctx.strokeRect(bx + 1, by + 1, bw - 2, bw - 2);
          ctx.restore();
        }

        // Selected cell ring
        if (selectedCell) {
          const sx = ox + selectedCell.c * cellSize;
          const sy = oy + selectedCell.r * cellSize;
          ctx.strokeStyle = c.selectStroke;
          ctx.lineWidth = 2.2;
          ctx.strokeRect(sx + 1, sy + 1, cellSize - 2, cellSize - 2);
        }
      }

      redrawCurrent = draw;
      draw();

      function coordsToCell(clientX, clientY) {
        const rect = canvas.getBoundingClientRect();
        const x = clientX - rect.left;
        const y = clientY - rect.top;
        const ox = pad + labelW;
        const oy = pad + headerH;
        const r = Math.floor((y - oy) / cellSize);
        const c = Math.floor((x - ox) / cellSize);
        if (r >= 0 && r < n && x >= pad && x < ox) {
          return { r, c: r }; // Clicking/hovering row label targets diagonal item
        }
        if (r >= 0 && r < n && c >= 0 && c < n) return { r, c };
        return null;
      }

      canvas.addEventListener('pointermove', ev => {
        const hit = coordsToCell(ev.clientX, ev.clientY);
        const nr = hit ? hit.r : -1;
        const nc = hit ? hit.c : -1;
        if (nr !== hoverR || nc !== hoverC) {
          hoverR = nr;
          hoverC = nc;
          draw();
          if (!selectedCell && hit) updateDetailBar(hit.r, hit.c, false);
        }
      });

      canvas.addEventListener('pointerleave', () => {
        if (hoverR !== -1 || hoverC !== -1) {
          hoverR = hoverC = -1;
          draw();
        }
      });

      canvas.addEventListener('click', ev => {
        const hit = coordsToCell(ev.clientX, ev.clientY);
        if (!hit) return;
        selectedCell = hit;
        draw();
        updateDetailBar(hit.r, hit.c, true);
      });

      // Accessible SCC Cycle Blocks list underneath for keyboard/mobile inspection
      const cyclicBlocks = data.sccBlocks.filter(b => b.cyclic && b.size > 1);
      const sccList = el('div', 'evo-dsm-scc-list');
      if (cyclicBlocks.length) {
        sccList.append(el('h3', '', `Strongly Connected Cycle Blocks (${cyclicBlocks.length})`));
        for (const block of cyclicBlocks.slice(0, 12)) {
          const memberItems = data.order.slice(block.start, block.end + 1);
          const memberIds = block.members.length ? block.members : memberItems.map(m => m.id);
          const memberNames = memberItems.map(m => shortDisplayLabel(m.label, 28));
          const info = append(
            el('div', 'evo-dsm-scc-info'),
            el('strong', '', `SCC Block #${block.index + 1} · ${block.size} tightly coupled ${data.granularity.toLowerCase()}s (rows ${block.start + 1}–${block.end + 1})`),
            el('p', '', memberNames.slice(0, 8).join(' ⇄ ') + (memberNames.length > 8 ? ` (+${memberNames.length - 8} more)` : ''))
          );
          const actions = append(
            el('div', 'evo-dsm-detail-actions'),
            btn('Focus on Dependency Map', () => {
              window.EvoBus?.emit('evo:canvas-focus', {
                nodeIds: memberIds,
                contextId: selectedContext,
                highlightReason: 'dsm-scc-block'
              });
            }, 'button subtle'),
            btn('Ask AI about cycle', () => {
              window.EvoBus?.emit('evo:chat-seed', {
                prompt: `Propose a concrete, minimal refactoring to break the ${data.granularity} strongly connected cycle among: ${memberNames.slice(0, 10).join(', ')}.`,
                focusNodeId: memberIds[0],
                contextId: selectedContext,
                mode: 'REPAIR'
              });
            }, 'button primary')
          );
          sccList.append(append(el('article', 'evo-dsm-scc-card'), info, actions));
        }
      }

      body.replaceChildren(stage, cyclicBlocks.length ? sccList : null);
    }

    async function loadDsm(autoFallback = true) {
      const current = ++version;
      redrawCurrent = null;
      kpiHost.replaceChildren();
      body.replaceChildren(empty('Computing levelized DSM…', `Running iterative Tarjan SCC condensation at ${granularity.toLowerCase()} granularity.`));
      try {
        const res = await api('/graphs/' + env.graph + '/queries/dsm', {
          context: selectedContext,
          granularity,
          maxItems
        }, env.signal);
        if (env.signal.aborted || current !== version) return;
        const norm = normalizeDsm(res);
        if (autoFallback && !norm.order.length && granularity === 'PACKAGE') {
          granularity = 'TYPE';
          syncGranButtons();
          return loadDsm(false);
        }
        renderDsmMatrix(res);
      } catch (err) {
        if (env.signal.aborted || current !== version) return;
        body.replaceChildren(
          empty('DSM Matrix unavailable', err.message || 'Could not compute the dependency structure matrix for this context.'),
          btn('Retry DSM', () => loadDsm(false))
        );
      }
    }

    return { node: root, after: () => safe(() => loadDsm(true)) };
  }

  const api = Object.freeze({
    renderView,
    normalizeDsm,
    summarizeDsm
  });

  if (typeof window !== 'undefined') {
    window.EvoDsmMatrix = api;
  }
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})();
