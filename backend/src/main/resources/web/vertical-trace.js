/* Layer-3 Full-Stack 8-Lane Vertical Trace Visualizer & Lazy CFG/DFG/CDG Code Slice Drawer.
 * Pure DOM builders only (0 external CDN, strict CSP, dark/light tokens, 320px-1440px).
 * Consumes POST /api/v1/graphs/{graph}/queries/vertical-trace & /queries/code-slice via host.api.
 */
(() => {
  'use strict';

  const CANONICAL_LAYERS = Object.freeze([
    Object.freeze({ id: 'FRONTEND', label: 'Frontend', shortLabel: '01 · UI', tone: 'frontend', advisoryLane: false }),
    Object.freeze({ id: 'API_ROUTE', label: 'API Route', shortLabel: '02 · API', tone: 'api', advisoryLane: false }),
    Object.freeze({ id: 'SPRING_CONTROLLER', label: 'Spring Controller', shortLabel: '03 · Ctrl', tone: 'controller', advisoryLane: false }),
    Object.freeze({ id: 'SPRING_DI', label: 'Spring DI', shortLabel: '04 · DI', tone: 'spring', advisoryLane: false }),
    Object.freeze({ id: 'SERVICE', label: 'Service', shortLabel: '05 · Svc', tone: 'service', advisoryLane: false }),
    Object.freeze({ id: 'JPA_ENTITY', label: 'JPA Entity', shortLabel: '06 · JPA', tone: 'jpa', advisoryLane: false }),
    Object.freeze({ id: 'SQL_TABLE', label: 'SQL Table', shortLabel: '07 · SQL', tone: 'sql', advisoryLane: true }),
    Object.freeze({ id: 'TERRAFORM_RESOURCE', label: 'Terraform', shortLabel: '08 · IaC', tone: 'terraform', advisoryLane: true })
  ]);

  const LAYER_INDEX = new Map(CANONICAL_LAYERS.map((l, idx) => [l.id, idx]));
  const VALID_CLASSIFICATIONS = new Set(['MUST', 'MAY', 'NEVER', 'UNKNOWN']);
  const VALID_SLICE_DIRS = new Set(['BACKWARD', 'FORWARD', 'BOTH']);
  const VALID_DEP_KINDS = new Set(['SEED', 'DATA', 'CONTROL', 'BOTH']);
  const VALID_TIERS = new Set(['COMPILER', 'SPRING_SAT', 'SOURCE_SYNTAX', 'ADVISORY_UNVERIFIED']);

  let pendingTracePayload = null;
  let activeTraceController = null;

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

  function normalizeTrustTier(raw, layer) {
    const isAdvLayer = layer === 'SQL_TABLE' || layer === 'TERRAFORM_RESOURCE';
    if (typeof window !== 'undefined' && window.EvoTrust?.normalize) {
      if (typeof raw === 'string' && raw.trim()) {
        const norm = window.EvoTrust.normalize(raw);
        return isAdvLayer ? 'ADVISORY_UNVERIFIED' : norm;
      }
      return isAdvLayer ? 'ADVISORY_UNVERIFIED' : 'SOURCE_SYNTAX';
    }
    if (isAdvLayer) return 'ADVISORY_UNVERIFIED';
    if (typeof raw !== 'string' || !raw.trim()) return 'SOURCE_SYNTAX';
    const cleaned = raw.trim().toUpperCase().replace(/[\s-]+/g, '_');
    if (VALID_TIERS.has(cleaned)) return cleaned;
    if (cleaned.includes('ADVISORY') || cleaned.includes('UNVERIFIED')) return 'ADVISORY_UNVERIFIED';
    if (cleaned.includes('SPRING') || cleaned === 'SAT') return 'SPRING_SAT';
    return 'SOURCE_SYNTAX';
  }

  function normalizeLayer(raw) {
    if (typeof raw !== 'string' || !raw.trim()) return 'SERVICE';
    const key = raw.trim().toUpperCase().replace(/[\s-]+/g, '_');
    if (LAYER_INDEX.has(key)) return key;
    if (key.includes('FRONT') || key === 'UI' || key === 'WEB') return 'FRONTEND';
    if (key.includes('ROUTE') || key.includes('ENDPOINT') || key === 'API') return 'API_ROUTE';
    if (key.includes('CONTROLLER')) return 'SPRING_CONTROLLER';
    if (key.includes('SPRING') || key.includes('BEAN') || key === 'DI') return 'SPRING_DI';
    if (key.includes('JPA') || key.includes('ENTITY') || key.includes('REPO')) return 'JPA_ENTITY';
    if (key.includes('SQL') || key.includes('TABLE') || key.includes('DDL')) return 'SQL_TABLE';
    if (key.includes('TERRAFORM') || key.includes('IAC') || key === 'TF') return 'TERRAFORM_RESOURCE';
    return 'SERVICE';
  }

  function positiveIntOrNull(val) {
    const n = Number(val);
    return Number.isFinite(n) && n > 0 ? Math.floor(n) : null;
  }

  function normalizeHop(raw, idx = 0) {
    const src = raw && typeof raw === 'object' ? raw : {};
    const layer = normalizeLayer(src.layer);
    const trustTier = normalizeTrustTier(src.trustTier, layer);
    const rawClass = typeof src.classification === 'string' ? src.classification.trim().toUpperCase() : '';
    const classification = VALID_CLASSIFICATIONS.has(rawClass) ? rawClass : null;
    const startLine = positiveIntOrNull(src.startLine ?? src.sourceSpan?.startLine);
    const endLine = positiveIntOrNull(src.endLine ?? src.sourceSpan?.endLine) ?? startLine;
    const sourcePath = typeof (src.sourcePath ?? src.sourceSpan?.path ?? src.path) === 'string'
      ? String(src.sourcePath ?? src.sourceSpan?.path ?? src.path).trim() || null
      : null;

    return {
      index: Number.isFinite(Number(src.index)) ? Number(src.index) : idx,
      layer,
      nodeId: String(src.nodeId || src.id || '').trim(),
      label: String(src.label || src.nodeId || ('Hop ' + (idx + 1))),
      kind: String(src.kind || '').trim(),
      trustTier,
      isAdvisory: trustTier === 'ADVISORY_UNVERIFIED',
      classification,
      witnessId: src.witnessId ? String(src.witnessId).trim() : null,
      sourcePath,
      startLine,
      endLine,
      contentSha256: src.contentSha256 ? String(src.contentSha256).trim() : null,
      edgeKind: src.edgeKind ? String(src.edgeKind).trim() : null,
      gapReason: src.gapReason ? String(src.gapReason).trim() : null
    };
  }

  function normalizeGap(raw, idx = 0) {
    if (typeof raw === 'string') {
      return {
        index: idx,
        gapId: null,
        code: 'CAPABILITY_GAP',
        mechanismCategory: null,
        layer: null,
        nodeId: null,
        affectedNodeIds: [],
        sourcePaths: [],
        limitations: [],
        reason: raw.trim()
      };
    }
    const g = raw && typeof raw === 'object' ? raw : {};
    const layer = g.layer ? normalizeLayer(g.layer) : null;
    const code = String(g.code || g.reasonCode || 'CAPABILITY_GAP').trim();
    const mechanismCategory = g.mechanismCategory ? String(g.mechanismCategory).trim() : null;
    const affectedNodeIds = Array.isArray(g.affectedNodeIds)
      ? g.affectedNodeIds.map(x => String(x || '').trim()).filter(Boolean)
      : [];
    const sourcePaths = Array.isArray(g.sourcePaths)
      ? g.sourcePaths.map(x => String(x || '').trim()).filter(Boolean)
      : [];
    const limitations = Array.isArray(g.limitations)
      ? g.limitations.map(x => String(x || '').trim()).filter(Boolean)
      : [];
    const explicitReason = String(g.reason || g.message || g.detail || '').trim();
    let reason = explicitReason;
    if (!reason) {
      const parts = [];
      if (mechanismCategory) parts.push(mechanismCategory);
      if (limitations.length) parts.push(limitations.join('; '));
      if (sourcePaths.length) {
        const shown = sourcePaths.slice(0, 3).join(', ');
        parts.push(sourcePaths.length > 3 ? `${shown} (+${sourcePaths.length - 3} more)` : shown);
      }
      if (affectedNodeIds.length) {
        parts.push(`${affectedNodeIds.length} affected node${affectedNodeIds.length === 1 ? '' : 's'}`);
      }
      reason = parts.join(' · ') || code || 'Capability gap recorded.';
    }
    return {
      index: idx,
      gapId: g.gapId ? String(g.gapId).trim() : null,
      code,
      mechanismCategory,
      layer,
      nodeId: g.nodeId ? String(g.nodeId).trim() : (affectedNodeIds[0] || null),
      affectedNodeIds,
      sourcePaths,
      limitations,
      reason
    };
  }

  function normalizeTrace(raw) {
    const data = raw && typeof raw === 'object' ? raw : {};
    const hops = Array.isArray(data.hops)
      ? data.hops.map((h, i) => normalizeHop(h, i)).sort((a, b) => a.index - b.index)
      : [];
    const gaps = Array.isArray(data.gaps)
      ? data.gaps.map((g, i) => normalizeGap(g, i)).filter(g => g.reason)
      : [];
    const truncated = Boolean(data.truncated);

    const derivedCanonical = hops.filter(h => !h.isAdvisory).length;
    const derivedAdvisory = hops.filter(h => h.isAdvisory).length;

    const rawCanonical = Number(data.canonicalHopCount);
    const rawAdvisory = Number(data.advisoryHopCount);
    const rawTotal = Number(data.totalHops);

    const canonicalHopCount = hops.length > 0 && !truncated
      ? derivedCanonical
      : (Number.isFinite(rawCanonical) && rawCanonical >= derivedCanonical ? Math.floor(rawCanonical) : derivedCanonical);
    const advisoryHopCount = hops.length > 0 && !truncated
      ? derivedAdvisory
      : (Number.isFinite(rawAdvisory) && rawAdvisory >= derivedAdvisory ? Math.floor(rawAdvisory) : derivedAdvisory);
    const totalHops = hops.length > 0 && !truncated
      ? hops.length
      : (Number.isFinite(rawTotal) && rawTotal >= (canonicalHopCount + advisoryHopCount)
        ? Math.floor(rawTotal)
        : (canonicalHopCount + advisoryHopCount));

    const hopGaps = hops
      .filter(h => h.gapReason)
      .map((h, i) => ({
        index: gaps.length + i,
        code: 'HOP_GAP',
        layer: h.layer,
        nodeId: h.nodeId || null,
        reason: `${h.label}: ${h.gapReason}`
      }));

    return {
      graphDigest: String(data.graphDigest || ''),
      context: String(data.context || ''),
      seed: String(data.seed || ''),
      totalHops,
      canonicalHopCount,
      advisoryHopCount,
      truncated,
      layers: CANONICAL_LAYERS.map(l => l.id),
      hops,
      gaps,
      allGaps: [...gaps, ...hopGaps]
    };
  }

  function groupHopsByLane(traceOrRaw) {
    const trace = traceOrRaw && Array.isArray(traceOrRaw.allGaps) && Array.isArray(traceOrRaw.layers)
      ? traceOrRaw
      : normalizeTrace(traceOrRaw);

    const buckets = new Map(CANONICAL_LAYERS.map(def => [def.id, []]));
    for (const hop of trace.hops) {
      const target = buckets.get(hop.layer) || buckets.get('SERVICE');
      target.push(hop);
    }

    return CANONICAL_LAYERS.map(def => {
      const laneHops = buckets.get(def.id) || [];
      const laneGaps = trace.gaps.filter(g => g.layer === def.id);
      const hopGapCount = laneHops.filter(h => Boolean(h.gapReason)).length;
      const advisoryCount = laneHops.filter(h => h.isAdvisory).length;
      const canonicalCount = laneHops.length - advisoryCount;
      return {
        ...def,
        hops: laneHops,
        count: laneHops.length,
        canonicalCount,
        advisoryCount,
        hasGap: laneGaps.length > 0 || hopGapCount > 0,
        gapCount: laneGaps.length + hopGapCount,
        gaps: laneGaps
      };
    });
  }

  function normalizeDepKind(raw, isSeed) {
    if (typeof raw === 'string' && raw.trim()) {
      const up = raw.trim().toUpperCase();
      if (VALID_DEP_KINDS.has(up)) return up;
      if (up.includes('DATA') && up.includes('CTRL')) return 'BOTH';
      if (up.includes('DATA')) return 'DATA';
      if (up.includes('CONTROL') || up.includes('CTRL')) return 'CONTROL';
      if (up.includes('SEED')) return 'SEED';
    }
    return isSeed ? 'SEED' : 'DATA';
  }

  function normalizeSlice(raw) {
    const data = raw && typeof raw === 'object' ? raw : {};
    const seedLine = positiveIntOrNull(data.seedLine ?? data.line) ?? 1;
    const rawDir = String(data.direction || 'BACKWARD').trim().toUpperCase();
    const direction = VALID_SLICE_DIRS.has(rawDir) ? rawDir : 'BACKWARD';
    const trustTier = normalizeTrustTier(data.trustTier, 'SERVICE');

    const statements = Array.isArray(data.statements)
      ? data.statements.map(s => {
        const line = positiveIntOrNull(s?.line) ?? seedLine;
        const endLine = positiveIntOrNull(s?.endLine) ?? line;
        const isSeed = line === seedLine || String(s?.dependencyKind || '').toUpperCase() === 'SEED';
        const dependencyKind = normalizeDepKind(s?.dependencyKind, isSeed);
        const dependsOnLines = Array.isArray(s?.dependsOnLines)
          ? [...new Set(s.dependsOnLines.map(positiveIntOrNull).filter(Boolean))].sort((a, b) => a - b)
          : [];
        return {
          line,
          endLine,
          kind: String(s?.kind || 'STATEMENT').trim(),
          code: String(s?.code ?? ''),
          dependsOnLines,
          dependencyKind,
          isSeed: dependencyKind === 'SEED' || line === seedLine
        };
      }).sort((a, b) => a.line - b.line)
      : [];

    const lineSet = new Set();
    if (Array.isArray(data.sliceLines)) {
      data.sliceLines.forEach(n => {
        const parsed = positiveIntOrNull(n);
        if (parsed) lineSet.add(parsed);
      });
    }
    statements.forEach(s => lineSet.add(s.line));
    const sliceLines = [...lineSet].sort((a, b) => a - b);

    const byKind = { SEED: 0, DATA: 0, CONTROL: 0, BOTH: 0 };
    const lineMeta = {};
    for (const stmt of statements) {
      byKind[stmt.dependencyKind] = (byKind[stmt.dependencyKind] || 0) + 1;
      lineMeta[stmt.line] = {
        line: stmt.line,
        endLine: stmt.endLine,
        dependencyKind: stmt.dependencyKind,
        isSeed: stmt.isSeed,
        dependsOnLines: stmt.dependsOnLines,
        kind: stmt.kind
      };
    }

    const limitations = Array.isArray(data.limitations)
      ? data.limitations.map(x => String(x || '').trim()).filter(Boolean)
      : [];

    const minLine = sliceLines.length ? sliceLines[0] : seedLine;
    const maxLine = statements.length
      ? Math.max(...statements.map(s => s.endLine), ...(sliceLines.length ? [sliceLines[sliceLines.length - 1]] : [seedLine]))
      : (sliceLines.length ? sliceLines[sliceLines.length - 1] : seedLine);

    return {
      path: String(data.path || '').trim(),
      methodSignature: String(data.methodSignature || '').trim(),
      seedLine,
      direction,
      trustTier,
      truncated: Boolean(data.truncated),
      totalMethodStatements: Number.isFinite(Number(data.totalMethodStatements))
        ? Math.max(statements.length, Math.floor(Number(data.totalMethodStatements)))
        : statements.length,
      statements,
      sliceLines,
      limitations,
      highlightMeta: {
        seedLine,
        minLine,
        maxLine,
        lineCount: sliceLines.length,
        byKind,
        lineMeta
      }
    };
  }

  function formatSpanChip(path, startLine, endLine) {
    if (!path) return '';
    const shortPath = path.length > 42 ? '…/' + path.split('/').slice(-2).join('/') : path;
    if (!startLine) return shortPath;
    if (endLine && endLine !== startLine) return `${shortPath}:${startLine}-${endLine}`;
    return `${shortPath}:${startLine}`;
  }

  function renderTrustBadge(tier, opts = {}) {
    if (typeof window !== 'undefined' && window.EvoTrust?.renderBadge) {
      return window.EvoTrust.renderBadge(tier, opts);
    }
    const pill = el('span', 'evo-trust-badge', tier);
    if (opts.title) pill.title = opts.title;
    return pill;
  }

  function buildSeedLabel(raw) {
    const s = String(raw || '').trim();
    if (!s) return '';
    if (s.length <= 34) return s;
    const parts = s.split(/[./]/);
    return parts.length > 1 ? parts.slice(-2).join('.') : s.slice(-32);
  }

  async function renderView(env, host) {
    const { api, state, safe, btn, card, empty, defaultDependencyContext, download } = host;
    const selectedContext = defaultDependencyContext ? defaultDependencyContext(env) : env.context;

    const root = card(
      'Full-Stack Vertical Trace',
      'Trace execution and data flow across 8 architectural layers from Frontend and API Routes through Spring DI, Services, and JPA down to quarantined Advisory SQL & Terraform overlays, with lazy intra-procedural CFG/DFG/CDG code slicing.'
    );
    root.classList.add('evo-vtrace-view');

    let traceVersion = 0;
    let sliceVersion = 0;
    let lastRawTrace = null;
    let currentSeed = '';
    let currentSeedKind = '';
    let currentWorld = '';
    let maxDepth = 8;
    let includeAdvisory = true;
    let activeSliceReq = null;

    // 1. Query & Seed Bar
    const queryForm = el('form', 'evo-vtrace-query-bar');
    queryForm.setAttribute('aria-label', 'Vertical trace query');

    const seedWrap = el('label', 'evo-vtrace-field evo-vtrace-seed-field');
    seedWrap.append(el('span', 'evo-vtrace-field-label', 'Trace seed'));
    const seedInput = el('input', 'evo-vtrace-seed-input');
    seedInput.type = 'search';
    seedInput.placeholder = 'Symbol, endpoint (/api/orders), JPA entity, or source path…';
    seedInput.autocomplete = 'off';
    seedInput.spellcheck = false;
    seedInput.setAttribute('aria-label', 'Vertical trace seed symbol, endpoint, entity, or path');
    seedWrap.append(seedInput);

    const depthWrap = el('label', 'evo-vtrace-field evo-vtrace-depth-field');
    depthWrap.append(el('span', 'evo-vtrace-field-label', 'Max depth'));
    const depthSelect = el('select', 'evo-vtrace-select');
    depthSelect.setAttribute('aria-label', 'Maximum trace depth');
    for (const d of [4, 8, 12]) {
      const opt = el('option', '', String(d) + ' hops');
      opt.value = String(d);
      if (d === maxDepth) opt.selected = true;
      depthSelect.append(opt);
    }
    depthSelect.addEventListener('change', () => {
      maxDepth = Number(depthSelect.value) || 8;
      if (seedInput.value.trim()) void runTrace();
    });
    depthWrap.append(depthSelect);

    const advWrap = el('label', 'evo-vtrace-adv-toggle');
    const advCheckbox = el('input', 'evo-vtrace-checkbox');
    advCheckbox.type = 'checkbox';
    advCheckbox.checked = includeAdvisory;
    advCheckbox.setAttribute('aria-label', 'Include Advisory SQL and Terraform layers');
    advCheckbox.addEventListener('change', () => {
      includeAdvisory = advCheckbox.checked;
      if (seedInput.value.trim()) void runTrace();
    });
    advWrap.append(
      advCheckbox,
      el('span', 'evo-vtrace-adv-label', 'Include Advisory (SQL & Terraform)'),
      renderTrustBadge('ADVISORY_UNVERIFIED', {
        compact: true,
        title: 'SQL tables and Terraform resources are quarantined ADVISORY_UNVERIFIED overlays and never mutate the canonical KnowledgeGraph.'
      })
    );

    const traceBtn = btn('Trace', null, 'button primary');
    traceBtn.type = 'submit';
    const downloadBtn = btn('Download JSON', () => {
      if (lastRawTrace) download(lastRawTrace, 'vertical-trace.json');
    }, 'button subtle');
    downloadBtn.hidden = true;

    const actionsRow = append(el('div', 'evo-vtrace-query-actions'), traceBtn, downloadBtn);
    queryForm.append(seedWrap, depthWrap, advWrap, actionsRow);

    // Quick-pick seed chips
    const quickBar = el('div', 'evo-vtrace-quick-bar');
    quickBar.setAttribute('aria-label', 'Quick-pick trace seeds');
    const quickChipsHost = el('div', 'evo-vtrace-quick-chips');
    quickBar.append(el('span', 'evo-vtrace-quick-label', 'Quick seeds'), quickChipsHost);
    quickBar.hidden = true;

    // Summary, Gap Strip, 8-Lane Board, Lazy Slice Drawer
    const summaryStrip = el('div', 'evo-vtrace-summary');
    summaryStrip.setAttribute('role', 'status');
    summaryStrip.setAttribute('aria-live', 'polite');

    const gapStrip = el('section', 'evo-vtrace-gap-strip');
    gapStrip.setAttribute('aria-label', 'Capability gaps');
    gapStrip.hidden = true;

    const boardHost = el('div', 'evo-vtrace-board-host');
    const sliceDrawerHost = el('section', 'evo-vtrace-slice-drawer');
    sliceDrawerHost.setAttribute('aria-label', 'Lazy CFG/DFG/CDG Code Slice');
    sliceDrawerHost.hidden = true;

    root.append(queryForm, quickBar, summaryStrip, gapStrip, boardHost, sliceDrawerHost);

    queryForm.addEventListener('submit', ev => {
      ev.preventDefault();
      currentSeedKind = '';
      void runTrace();
    });

    function renderQuickChips(candidates) {
      const unique = [];
      const seen = new Set();
      for (const c of candidates) {
        if (!c?.seed || seen.has(c.seed)) continue;
        seen.add(c.seed);
        unique.push(c);
        if (unique.length >= 8) break;
      }
      if (!unique.length) {
        quickBar.hidden = true;
        quickChipsHost.replaceChildren();
        return;
      }
      quickBar.hidden = false;
      quickChipsHost.replaceChildren(...unique.map(c => {
        const chip = btn('', () => {
          seedInput.value = c.seed;
          currentSeedKind = c.seedKind || '';
          void runTrace();
        }, 'evo-vtrace-seed-chip');
        chip.title = c.seed;
        if (c.badge) chip.append(el('span', 'evo-vtrace-chip-kind', c.badge));
        chip.append(el('span', '', buildSeedLabel(c.label || c.seed)));
        return chip;
      }));
    }

    async function loadQuickSeeds() {
      const seeds = [];
      try {
        const repoMap = await api('/graphs/' + env.graph + '/repo-map', {
          context: selectedContext,
          maxFiles: 12
        }, env.signal);
        if (env.signal.aborted) return;
        const files = Array.isArray(repoMap?.files) ? repoMap.files : [];
        for (const f of files) {
          if (Array.isArray(f.endpoints)) {
            for (const ep of f.endpoints.slice(0, 2)) {
              const epText = typeof ep === 'string' ? ep : (ep?.path || ep?.route || ep?.label || '');
              if (epText) seeds.push({ seed: epText, label: epText, seedKind: 'API_ROUTE', badge: 'API' });
            }
          }
          if (Array.isArray(f.declarations)) {
            for (const d of f.declarations.slice(0, 2)) {
              const name = d?.qualifiedName || d?.name || '';
              if (name) seeds.push({ seed: name, label: d?.name || name, seedKind: d?.kind || 'TYPE', badge: 'SYM' });
            }
          }
        }
      } catch (_) {
        // Fallback silently to cached labels in state.labels
      }
      if (seeds.length < 6 && state?.labels instanceof Map) {
        for (const [id, lbl] of state.labels.entries()) {
          if (!lbl || /^sha256:/i.test(lbl)) continue;
          seeds.push({ seed: id, label: lbl, seedKind: '', badge: 'NODE' });
          if (seeds.length >= 8) break;
        }
      }
      if (!env.signal.aborted) renderQuickChips(seeds);
    }

    function renderGapBanner(trace) {
      if (!trace.allGaps.length) {
        gapStrip.hidden = true;
        gapStrip.replaceChildren();
        return;
      }
      gapStrip.hidden = false;
      const head = append(
        el('div', 'evo-vtrace-gap-head'),
        el('strong', '', `Explicit Capability Gaps (${trace.allGaps.length})`),
        el('span', 'evo-vtrace-gap-sub', 'Unresolved hops or missing evidence are preserved transparently rather than omitted.')
      );
      const list = el('ul', 'evo-vtrace-gap-list');
      for (const g of trace.allGaps.slice(0, 12)) {
        const li = el('li', 'evo-vtrace-gap-item');
        if (g.layer) li.append(el('span', 'evo-vtrace-gap-layer', g.layer));
        li.append(el('code', 'evo-vtrace-gap-code', g.code), el('span', '', g.reason));
        list.append(li);
      }
      gapStrip.replaceChildren(head, list);
    }

    function openSliceForHop(hop, direction = 'BACKWARD', overrideLine = null) {
      const path = hop.sourcePath;
      const line = positiveIntOrNull(overrideLine) ?? hop.startLine ?? 1;
      if (!path) {
        sliceDrawerHost.hidden = false;
        sliceDrawerHost.replaceChildren(
          append(
            el('div', 'evo-vtrace-slice-head'),
            el('h3', '', 'Lazy Code Slice unavailable'),
            btn('Close', () => { sliceDrawerHost.hidden = true; }, 'button subtle')
          ),
          empty('No captured source path on this hop', 'This hop does not carry a captured Java source path to run intra-procedural CFG/DFG/CDG slicing.')
        );
        return;
      }
      activeSliceReq = {
        hop,
        path,
        methodSignature: hop.kind === 'METHOD' ? hop.label : undefined,
        line,
        direction: VALID_SLICE_DIRS.has(direction) ? direction : 'BACKWARD',
        maxStatements: 80
      };
      void runCodeSlice(activeSliceReq);
    }

    async function runCodeSlice(req) {
      const version = ++sliceVersion;
      sliceDrawerHost.hidden = false;
      sliceDrawerHost.replaceChildren(
        append(
          el('div', 'evo-vtrace-slice-head'),
          append(
            el('div', 'evo-vtrace-slice-title-wrap'),
            el('h3', '', 'Lazy CFG / DFG / CDG Code Slice'),
            el('code', 'evo-vtrace-slice-path', `${req.path}:${req.line}`)
          ),
          btn('Close', () => { sliceDrawerHost.hidden = true; activeSliceReq = null; }, 'button subtle')
        ),
        empty('Computing intra-procedural slice…', `Analyzing ${req.direction.toLowerCase()} data & control dependencies from line ${req.line} (zero canonical graph mutation).`)
      );
      sliceDrawerHost.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' });

      try {
        const body = {
          path: req.path,
          line: req.line,
          direction: req.direction,
          maxStatements: req.maxStatements || 80
        };
        if (req.methodSignature) body.methodSignature = req.methodSignature;

        const raw = await api('/graphs/' + env.graph + '/queries/code-slice', body, env.signal);
        if (env.signal.aborted || version !== sliceVersion) return;
        renderSliceDrawer(normalizeSlice(raw), req);
      } catch (err) {
        if (env.signal.aborted || version !== sliceVersion) return;
        sliceDrawerHost.replaceChildren(
          append(
            el('div', 'evo-vtrace-slice-head'),
            append(
              el('div', 'evo-vtrace-slice-title-wrap'),
              el('h3', '', 'Lazy CFG / DFG / CDG Code Slice'),
              el('code', 'evo-vtrace-slice-path', `${req.path}:${req.line}`)
            ),
            btn('Close', () => { sliceDrawerHost.hidden = true; activeSliceReq = null; }, 'button subtle')
          ),
          empty('Code slice unavailable', err.message || 'Could not compute an intra-procedural slice for this source line.'),
          append(
            el('div', 'evo-vtrace-slice-toolbar'),
            btn('Retry slice', () => runCodeSlice(req), 'button subtle'),
            btn('Open Full Source', () => {
              window.EvoBus?.emit('evo:source-open', {
                path: req.path,
                startLine: Math.max(1, req.line - 20),
                endLine: req.line + 60,
                highlightLine: req.line,
                sliceLines: [req.line]
              });
            }, 'button primary')
          )
        );
      }
    }

    function renderSliceDrawer(slice, req) {
      const headLeft = append(
        el('div', 'evo-vtrace-slice-title-wrap'),
        append(
          el('div', 'evo-vtrace-slice-heading-row'),
          el('h3', '', 'Lazy CFG / DFG / CDG Code Slice'),
          renderTrustBadge(slice.trustTier, { compact: true }),
          el('span', 'evo-vtrace-kpi-pill', `${slice.statements.length} / ${slice.totalMethodStatements} stmts`),
          slice.truncated ? el('span', 'evo-vtrace-kpi-pill is-warn', 'Truncated') : null
        ),
        append(
          el('div', 'evo-vtrace-slice-meta-row'),
          el('code', 'evo-vtrace-slice-path', slice.path || req.path),
          slice.methodSignature ? el('span', 'evo-vtrace-slice-method', slice.methodSignature) : null
        )
      );

      const closeBtn = btn('Close', () => {
        sliceDrawerHost.hidden = true;
        activeSliceReq = null;
      }, 'button subtle');
      const head = append(el('div', 'evo-vtrace-slice-head'), headLeft, closeBtn);

      // Direction toggle + Seed line control + Open Full Source
      const dirGroup = el('div', 'evo-graph-seg-group');
      dirGroup.setAttribute('role', 'group');
      dirGroup.setAttribute('aria-label', 'Slice direction');
      dirGroup.append(el('span', 'evo-graph-seg-label', 'Direction'));
      for (const [dVal, dLabel] of [['BACKWARD', 'Backward'], ['FORWARD', 'Forward'], ['BOTH', 'Both']]) {
        const isActive = slice.direction === dVal;
        const b = btn(dLabel, () => {
          const nextReq = { ...req, direction: dVal, line: slice.seedLine };
          activeSliceReq = nextReq;
          void runCodeSlice(nextReq);
        }, 'evo-graph-seg-btn' + (isActive ? ' is-active' : ''));
        b.setAttribute('aria-pressed', String(isActive));
        dirGroup.append(b);
      }

      const lineForm = el('form', 'evo-vtrace-line-form');
      const lineInput = el('input', 'evo-vtrace-line-input');
      lineInput.type = 'number';
      lineInput.min = '1';
      lineInput.value = String(slice.seedLine);
      lineInput.setAttribute('aria-label', 'Seed line number');
      const reSliceBtn = btn('Re-slice', null, 'button subtle');
      reSliceBtn.type = 'submit';
      lineForm.append(el('span', 'evo-vtrace-field-label', 'Seed line'), lineInput, reSliceBtn);
      lineForm.addEventListener('submit', ev => {
        ev.preventDefault();
        const nextLine = positiveIntOrNull(lineInput.value) ?? slice.seedLine;
        const nextReq = { ...req, line: nextLine, direction: slice.direction };
        activeSliceReq = nextReq;
        void runCodeSlice(nextReq);
      });

      const openSourceBtn = btn('Open Full Source', () => {
        window.EvoBus?.emit('evo:source-open', {
          path: slice.path || req.path,
          startLine: Math.max(1, slice.highlightMeta.minLine - 10),
          endLine: slice.highlightMeta.maxLine + 20,
          highlightLine: slice.seedLine,
          sliceLines: slice.sliceLines
        });
      }, 'button primary');

      const toolbar = append(el('div', 'evo-vtrace-slice-toolbar'), dirGroup, lineForm, openSourceBtn);

      // Honest intra-procedural limitations banner
      const limitBanner = el('div', 'evo-vtrace-limitations');
      const limNotes = slice.limitations.length
        ? slice.limitations
        : ['Intra-procedural CFG/DFG/CDG slice over redacted captured source; inter-procedural aliasing and reflection are not inferred. Zero canonical graph mutation.'];
      limitBanner.append(
        el('strong', '', 'Slice scope & limitations: '),
        el('span', '', limNotes.join(' · '))
      );

      // Sliced statements viewer
      const codeBox = el('div', 'evo-vtrace-slice-code');
      codeBox.setAttribute('role', 'region');
      codeBox.setAttribute('aria-label', 'Sliced statements');

      const rowByLine = new Map();
      if (!slice.statements.length) {
        codeBox.append(empty('No sliced statements returned', 'Try switching slice direction or picking another line inside the method body.'));
      } else {
        for (const stmt of slice.statements) {
          const depClass = 'is-' + stmt.dependencyKind.toLowerCase();
          const row = el('div', 'evo-vtrace-stmt-row ' + depClass + (stmt.isSeed ? ' is-seed-line' : ''));
          row.dataset.line = String(stmt.line);
          rowByLine.set(stmt.line, row);

          const lnLabel = stmt.endLine && stmt.endLine !== stmt.line
            ? `L${stmt.line}–${stmt.endLine}`
            : `L${stmt.line}`;
          const lnSpan = el('span', 'evo-vtrace-stmt-line', lnLabel);
          const depPill = el('span', 'evo-vtrace-dep-pill ' + depClass, stmt.dependencyKind);
          const codeEl = el('code', 'evo-vtrace-stmt-code', stmt.code || ' ');

          const depsWrap = el('div', 'evo-vtrace-stmt-deps');
          if (stmt.dependsOnLines.length) {
            for (const depLine of stmt.dependsOnLines) {
              const depChip = btn('← L' + depLine, () => {
                for (const r of rowByLine.values()) r.classList.remove('is-dep-focus');
                const target = rowByLine.get(depLine);
                if (target) {
                  target.classList.add('is-dep-focus');
                  target.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' });
                }
              }, 'evo-vtrace-dep-chip');
              depChip.title = `Highlight dependency on line ${depLine}`;
              depsWrap.append(depChip);
            }
          }

          row.append(lnSpan, depPill, codeEl, depsWrap);
          codeBox.append(row);
        }
      }

      sliceDrawerHost.replaceChildren(head, toolbar, limitBanner, codeBox);
    }

    function renderHopCard(hop) {
      const cardEl = el('article', 'evo-vtrace-hop' + (hop.isAdvisory ? ' is-advisory' : '') + (hop.gapReason ? ' has-gap' : ''));

      const topRow = append(
        el('div', 'evo-vtrace-hop-top'),
        el('span', 'evo-vtrace-hop-idx', '#' + (hop.index + 1)),
        hop.edgeKind ? el('span', 'evo-vtrace-edge-chip', hop.edgeKind) : null
      );

      const title = el('h4', 'evo-vtrace-hop-title', hop.label);
      title.title = hop.label;
      const kindRow = hop.kind ? el('div', 'evo-vtrace-hop-kind', hop.kind) : null;

      // Trust badge + orthogonal classification + witnessId tooltip
      const trustTooltip = [
        hop.trustTier,
        hop.classification ? `Spring classification: ${hop.classification}` : null,
        hop.witnessId ? `Witness: ${hop.witnessId}` : null
      ].filter(Boolean).join(' · ');

      const badgeRow = append(
        el('div', 'evo-vtrace-hop-badges'),
        renderTrustBadge(hop.trustTier, {
          compact: true,
          classification: hop.classification || undefined,
          title: trustTooltip
        }),
        hop.witnessId
          ? (() => {
            const w = el('span', 'evo-vtrace-witness-chip', 'witness');
            w.title = `Minimized Spring SAT witness: ${hop.witnessId}`;
            return w;
          })()
          : null
      );

      // Source span chip
      let spanChip = null;
      if (hop.sourcePath) {
        spanChip = btn(formatSpanChip(hop.sourcePath, hop.startLine, hop.endLine), () => {
          window.EvoBus?.emit('evo:source-open', {
            path: hop.sourcePath,
            startLine: hop.startLine || 1,
            endLine: hop.endLine || (hop.startLine ? hop.startLine + 40 : 120),
            highlightLine: hop.startLine || 1
          });
        }, 'evo-vtrace-span-chip');
        spanChip.title = `${hop.sourcePath}${hop.startLine ? ':' + hop.startLine : ''}${hop.contentSha256 ? ' · sha256:' + hop.contentSha256.slice(0, 12) : ''}`;
      }

      // Inline hop capability gap strip
      const hopGap = hop.gapReason
        ? append(el('div', 'evo-vtrace-hop-gap'), el('strong', '', 'Gap: '), el('span', '', hop.gapReason))
        : null;

      // Per-hop actions
      const actions = el('div', 'evo-vtrace-hop-actions');

      if (hop.sourcePath) {
        actions.append(btn('Slice Code', () => openSliceForHop(hop, 'BACKWARD'), 'evo-vtrace-act-btn is-primary'));
      }

      if (!hop.isAdvisory && hop.nodeId) {
        actions.append(btn('Focus Map', () => {
          window.EvoBus?.emit('evo:canvas-focus', {
            nodeIds: [hop.nodeId],
            contextId: selectedContext,
            highlightReason: 'vertical-trace-hop'
          });
        }, 'evo-vtrace-act-btn'));
      }

      actions.append(btn('Ask AI', () => {
        const spanInfo = hop.sourcePath ? ` (${hop.sourcePath}${hop.startLine ? ':' + hop.startLine : ''})` : '';
        window.EvoBus?.emit('evo:chat-seed', {
          prompt: `Explain the role, trust evidence (${hop.trustTier}${hop.classification ? '/' + hop.classification : ''}), and downstream impact of \`${hop.label}\` in layer ${hop.layer}${spanInfo}.`,
          focusNodeId: !hop.isAdvisory && hop.nodeId ? hop.nodeId : undefined,
          contextId: selectedContext,
          mode: 'EXPLORE'
        });
      }, 'evo-vtrace-act-btn'));

      actions.append(btn('Plan Repair', () => {
        window.EvoBus?.emit('evo:repair-seed', {
          title: `Refactor ${hop.label} (${hop.layer})`,
          description: `Seeded from Vertical Trace hop #${hop.index + 1} (${hop.layer}, ${hop.trustTier}).`,
          targetPaths: hop.sourcePath ? [hop.sourcePath] : [],
          targetSymbols: !hop.isAdvisory && hop.nodeId ? [hop.nodeId] : []
        });
      }, 'evo-vtrace-act-btn'));

      cardEl.append(...[topRow, title, kindRow, badgeRow, spanChip, hopGap, actions].filter(Boolean));
      return cardEl;
    }

    function renderTraceBoard(raw) {
      lastRawTrace = raw;
      downloadBtn.hidden = false;
      const trace = normalizeTrace(raw);
      const lanes = groupHopsByLane(trace);
      const activeLanes = lanes.filter(l => l.count > 0).length;

      summaryStrip.replaceChildren(
        el('span', 'evo-vtrace-kpi-pill', `Seed: ${buildSeedLabel(trace.seed || currentSeed)}`),
        el('span', 'evo-vtrace-kpi-pill is-good', `${trace.canonicalHopCount} canonical hop${trace.canonicalHopCount === 1 ? '' : 's'}`),
        el('span', 'evo-vtrace-kpi-pill is-advisory', `${trace.advisoryHopCount} advisory hop${trace.advisoryHopCount === 1 ? '' : 's'}`),
        el('span', 'evo-vtrace-kpi-pill', `${activeLanes} / 8 layers active`),
        trace.truncated ? el('span', 'evo-vtrace-kpi-pill is-warn', 'Truncated at maxDepth') : null
      );

      renderGapBanner(trace);

      if (!trace.hops.length) {
        boardHost.replaceChildren(empty(
          'No vertical trace hops found',
          'Try a different symbol, HTTP endpoint path, Spring controller, JPA entity, or pick one of the quick seeds above.'
        ));
        return;
      }

      const wrap = el('div', 'evo-vtrace-lanes-wrap');
      const grid = el('div', 'evo-vtrace-lanes');
      grid.setAttribute('role', 'region');
      grid.setAttribute('aria-label', '8-lane full-stack vertical trace');

      for (const lane of lanes) {
        const laneEl = el('section', 'evo-vtrace-lane is-' + lane.tone + (lane.advisoryLane ? ' is-advisory-lane' : '') + (lane.count === 0 ? ' is-empty' : ''));
        laneEl.dataset.layer = lane.id;

        const laneHead = append(
          el('header', 'evo-vtrace-lane-head'),
          append(
            el('div', 'evo-vtrace-lane-title-wrap'),
            el('span', 'evo-vtrace-lane-step', lane.shortLabel),
            el('h3', 'evo-vtrace-lane-title', lane.label)
          ),
          el('span', 'evo-vtrace-lane-count', String(lane.count))
        );

        const laneMeta = lane.advisoryLane
          ? el('div', 'evo-vtrace-lane-quarantine', 'Quarantined · Advisory Unverified')
          : null;

        const laneBody = el('div', 'evo-vtrace-lane-body');
        if (!lane.hops.length) {
          laneBody.append(el('p', 'evo-vtrace-lane-empty', lane.advisoryLane && !includeAdvisory
            ? 'Advisory layer hidden'
            : 'No hops in this layer'));
        } else {
          for (const hop of lane.hops) {
            laneBody.append(renderHopCard(hop));
          }
        }

        laneEl.append(...[laneHead, laneMeta, laneBody].filter(Boolean));
        grid.append(laneEl);
      }

      wrap.append(grid);
      boardHost.replaceChildren(wrap);
    }

    async function runTrace(overridePayload = null) {
      if (overridePayload && typeof overridePayload === 'object') {
        const nextSeed = String(overridePayload.seedId || overridePayload.seed || '').trim();
        if (nextSeed) seedInput.value = nextSeed;
        if (overridePayload.seedKind) currentSeedKind = String(overridePayload.seedKind);
        if (overridePayload.world != null) currentWorld = String(overridePayload.world || '');
        if (typeof overridePayload.includeAdvisory === 'boolean') {
          includeAdvisory = overridePayload.includeAdvisory;
          advCheckbox.checked = includeAdvisory;
        }
        if (positiveIntOrNull(overridePayload.maxDepth)) {
          maxDepth = positiveIntOrNull(overridePayload.maxDepth);
          depthSelect.value = String(maxDepth);
        }
      }

      const seed = seedInput.value.trim();
      if (!seed) {
        boardHost.replaceChildren(empty(
          'Enter or pick a vertical trace seed',
          'Start from a Frontend component, API route, Spring Controller, Service, or JPA Entity to trace all 8 layers.'
        ));
        return;
      }

      currentSeed = seed;
      const version = ++traceVersion;
      downloadBtn.hidden = true;
      gapStrip.hidden = true;
      sliceDrawerHost.hidden = true;
      summaryStrip.replaceChildren(el('span', 'evo-vtrace-kpi-pill', 'Tracing 8 full-stack layers…'));
      boardHost.replaceChildren(empty('Tracing full-stack path…', `Querying canonical graph + ${includeAdvisory ? 'quarantined advisory SQL/Terraform' : 'canonical-only'} hops for ${buildSeedLabel(seed)}.`));

      try {
        const reqBody = {
          context: selectedContext,
          seed,
          maxDepth,
          includeAdvisory
        };
        if (currentSeedKind) reqBody.seedKind = currentSeedKind;
        if (currentWorld) reqBody.world = currentWorld;

        const raw = await api('/graphs/' + env.graph + '/queries/vertical-trace', reqBody, env.signal);
        if (env.signal.aborted || version !== traceVersion) return;
        renderTraceBoard(raw);
      } catch (err) {
        if (env.signal.aborted || version !== traceVersion) return;
        summaryStrip.replaceChildren();
        boardHost.replaceChildren(
          empty('Vertical Trace unavailable', err.message || 'Could not execute the vertical trace query for this seed.'),
          btn('Retry trace', () => runTrace(), 'button subtle')
        );
      }
    }

    activeTraceController = {
      openSeed: payload => safe(() => runTrace(payload))
    };

    env.signal.addEventListener('abort', () => {
      activeTraceController = null;
    }, { once: true });

    const initialPayload = pendingTracePayload || state?.pendingTraceSeed || null;
    pendingTracePayload = null;
    if (state?.pendingTraceSeed) state.pendingTraceSeed = null;

    if (!initialPayload) {
      boardHost.replaceChildren(empty(
        'Trace a full-stack vertical slice',
        'Pick a quick seed above or enter a symbol, endpoint, JPA entity, or source path to inspect all 8 layers (Frontend → API → Spring → Service → JPA → SQL → Terraform).'
      ));
    }

    return {
      node: root,
      after: () => {
        void safe(loadQuickSeeds);
        if (initialPayload) void safe(() => runTrace(initialPayload));
      }
    };
  }

  // Subscribe once to EvoBus evo:trace-open so pre-seeded opens work whether mounted or switching tabs.
  if (typeof window !== 'undefined' && window.EvoBus?.on) {
    window.EvoBus.on('evo:trace-open', payload => {
      if (!payload || typeof payload !== 'object') return;
      if (activeTraceController) {
        activeTraceController.openSeed(payload);
      } else {
        pendingTracePayload = { ...payload };
      }
    });
  }

  const exported = Object.freeze({
    CANONICAL_LAYERS,
    render: renderView,
    renderView,
    normalizeLayer,
    normalizeHop,
    normalizeTrace,
    groupHopsByLane,
    normalizeSlice,
    formatSpanChip
  });

  if (typeof window !== 'undefined') {
    window.EvoVerticalTrace = exported;
  }
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = exported;
  }
})();
