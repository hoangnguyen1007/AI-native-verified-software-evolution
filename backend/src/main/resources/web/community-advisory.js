(() => {
  'use strict';

  function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = String(text);
    return n;
  }

  function append(parent, ...children) {
    for (const c of children) {
      if (c != null && c !== false) parent.append(c);
    }
    return parent;
  }

  function btn(label, onClick, cls = 'button subtle') {
    const b = el('button', cls, label);
    b.type = 'button';
    if (onClick) b.addEventListener('click', onClick);
    return b;
  }

  function triggerDownload(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  }

  function escapeXml(raw) {
    return String(raw ?? '')
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&apos;');
  }

  /**
   * Encodes a graph digest path segment while preserving the canonical "sha256:" colon unencoded
   * so ArchitectureHttpServer (which parses raw URI path segments) accepts it cleanly.
   */
  function graphPath(graphId) {
    return encodeURIComponent(String(graphId ?? '')).replaceAll('%3A', ':');
  }

  /**
   * 100% offline Cytoscape PNG export (zero CDN, zero external network calls).
   */
  function exportCytoscapePng(cy, filename = 'evo-dependency-map.png', bgColor = '#0b1120') {
    if (!cy || typeof cy.png !== 'function') return false;
    const dataUrl = cy.png({ full: true, scale: 2, bg: bgColor });
    const parts = dataUrl.split(',');
    if (parts.length < 2) return false;
    const bin = atob(parts[1]);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    triggerDownload(new Blob([bytes], { type: 'image/png' }), filename);
    return true;
  }

  /**
   * 100% offline deterministic SVG serializer directly from live Cytoscape geometry
   * (zero external libraries or CDN scripts; preserves zero-https CSP).
   */
  function buildCytoscapeSvg(cy, theme = {}) {
    if (!cy) return '';
    const bb = cy.elements(':visible').boundingBox();
    const pad = 44;
    const minX = Number.isFinite(bb.x1) ? bb.x1 - pad : 0;
    const minY = Number.isFinite(bb.y1) ? bb.y1 - pad : 0;
    const width = Math.max(320, Number.isFinite(bb.w) ? Math.ceil(bb.w + pad * 2) : 640);
    const height = Math.max(240, Number.isFinite(bb.h) ? Math.ceil(bb.h + pad * 2) : 480);

    const bg = theme.bg || '#0b1120';
    const textCol = theme.label || '#e2e8f0';
    const nodeFill = theme.node || '#1e293b';
    const nodeStroke = theme.border || '#475569';
    const edgeStroke = theme.edge || '#64748b';
    const groupFill = theme.group || '#1e293b';

    const lines = [
      `<?xml version="1.0" encoding="UTF-8"?>`,
      `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="${Math.floor(minX)} ${Math.floor(minY)} ${width} ${height}">`,
      `<defs><marker id="evo-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 1 L 10 5 L 0 9 z" fill="${escapeXml(edgeStroke)}"/></marker></defs>`,
      `<rect x="${Math.floor(minX)}" y="${Math.floor(minY)}" width="${width}" height="${height}" fill="${escapeXml(bg)}"/>`
    ];

    // 1. Compound containers (communities / packages / modules)
    cy.nodes(':visible').filter(n => n.isParent() || n.data('isSuperNode')).forEach(n => {
      const b = n.boundingBox({ includeLabels: false });
      if (!Number.isFinite(b.x1) || b.w <= 0) return;
      const isExternalGroup = n.data('kind') === 'EXTERNAL_GROUP' || Boolean(n.data('externalGroup'));
      const isComm = !isExternalGroup && (n.data('kind') === 'COMMUNITY' || Boolean(n.data('isSuperNode')));
      const fill = isComm ? 'rgba(99,102,241,0.16)' : groupFill;
      const stroke = isComm ? '#6366f1' : nodeStroke;
      const dash = isExternalGroup ? ' stroke-dasharray="5,3"' : '';
      lines.push(`<rect x="${b.x1.toFixed(1)}" y="${b.y1.toFixed(1)}" width="${b.w.toFixed(1)}" height="${b.h.toFixed(1)}" rx="10" fill="${escapeXml(fill)}" fill-opacity="0.45" stroke="${escapeXml(stroke)}" stroke-width="${isComm ? 1.8 : 1}"${dash}/>`);
      const lbl = n.data('label') || n.id();
      lines.push(`<text x="${(b.x1 + b.w / 2).toFixed(1)}" y="${(b.y1 + 16).toFixed(1)}" fill="${escapeXml(textCol)}" font-family="system-ui,sans-serif" font-size="11" font-weight="700" text-anchor="middle">${escapeXml(lbl)}</text>`);
    });

    // 2. Visible edges
    cy.edges(':visible').forEach(e => {
      const sp = e.sourceEndpoint();
      const tp = e.targetEndpoint();
      if (!sp || !tp || !Number.isFinite(sp.x) || !Number.isFinite(tp.x)) return;
      const isBridge = Boolean(e.data('isBridge'));
      const isCycle = Boolean(e.data('inCycle'));
      const stroke = isCycle ? '#ef4444' : isBridge ? '#06b6d4' : edgeStroke;
      const dash = e.data('classification') === 'MAY' ? ' stroke-dasharray="6,4"' : e.data('classification') === 'UNKNOWN' ? ' stroke-dasharray="2,3"' : '';
      lines.push(`<line x1="${sp.x.toFixed(1)}" y1="${sp.y.toFixed(1)}" x2="${tp.x.toFixed(1)}" y2="${tp.y.toFixed(1)}" stroke="${escapeXml(stroke)}" stroke-width="${isBridge || isCycle ? 2 : 1.3}" stroke-opacity="0.82"${dash} marker-end="url(#evo-arrow)"/>`);
    });

    // 3. Leaf nodes
    cy.nodes(':visible').filter(n => !n.isParent() && !n.data('isSuperNode')).forEach(n => {
      const p = n.position();
      const w = Number(n.width()) || 110;
      const h = Number(n.height()) || 34;
      const x = p.x - w / 2;
      const y = p.y - h / 2;
      const misplaced = Boolean(n.data('misplaced'));
      const cycle = Boolean(n.data('inCycle'));
      const stroke = cycle ? '#ef4444' : misplaced ? '#f59e0b' : nodeStroke;
      const dash = misplaced ? ' stroke-dasharray="4,3"' : '';
      lines.push(`<rect x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${w.toFixed(1)}" height="${h.toFixed(1)}" rx="7" fill="${escapeXml(nodeFill)}" stroke="${escapeXml(stroke)}" stroke-width="${misplaced || cycle ? 2.2 : 1.2}"${dash}/>`);
      const lbl = n.data('label') || n.id();
      lines.push(`<text x="${p.x.toFixed(1)}" y="${(p.y + 4).toFixed(1)}" fill="${escapeXml(textCol)}" font-family="system-ui,sans-serif" font-size="11" font-weight="600" text-anchor="middle">${escapeXml(lbl)}</text>`);
    });

    lines.push('</svg>');
    return lines.join('\n');
  }

  function exportCytoscapeSvg(cy, filename = 'evo-dependency-map.svg', theme = {}) {
    const svg = buildCytoscapeSvg(cy, theme);
    if (!svg) return false;
    triggerDownload(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }), filename);
    return true;
  }

  /**
   * Builds collapsible Leiden Community Super-Node elements for Cytoscape, allowing
   * smooth exploration of 1,000+ node graphs beyond the flat 220-node cap.
   */
  function buildCommunityElements(graph, collapsedSet, degreeMap, labelFn, fullLabelFn, preliminaryGraph) {
    const compounds = graph.nodes.filter(n => n.id.startsWith('compound:community:'));
    const externalCompound = graph.nodes.find(n => n.id === 'compound:external');
    const leaves = graph.nodes.filter(n => !n.id.startsWith('compound:'));
    const leafById = new Map(leaves.map(n => [n.id, n]));
    const commById = new Map(compounds.map(c => [c.id, c]));
    const containerById = new Map(commById);
    if (externalCompound) containerById.set(externalCompound.id, externalCompound);

    const visibleNodes = [];
    const includedLeafIds = new Set();
    const cycleCountByComm = new Map();
    for (const leaf of leaves) {
      if (leaf.inCycle && leaf.parent && commById.has(leaf.parent)) {
        cycleCountByComm.set(leaf.parent, (cycleCountByComm.get(leaf.parent) || 0) + 1);
      }
    }

    for (const c of compounds) {
      const collapsed = collapsedSet.has(c.id);
      const props = c.properties || {};
      const count = props.memberCount ?? 0;
      const cohPct = props.cohesion != null ? Math.round(Number(props.cohesion) * 100) : 0;
      const driftCount = props.misplacedCount ?? 0;
      const cycleCount = cycleCountByComm.get(c.id) || 0;
      const superLabel = collapsed
        ? `${c.label} (${count}) · ${cohPct}%` + (driftCount > 0 ? ` · ${driftCount} drift` : '')
        : `${c.label} (${count} members · ${cohPct}% cohesion)`;
      visibleNodes.push({
        data: {
          ...c,
          label: superLabel,
          fullLabel: c.label,
          isSuperNode: collapsed,
          communityCollapsed: collapsed,
          nodeWidth: collapsed ? Math.min(240, 130 + String(superLabel).length * 4) : 140,
          memberCount: count,
          cohesion: props.cohesion,
          dominantPackage: props.dominantPackage || '',
          misplacedCount: driftCount,
          inCycleCount: cycleCount,
          inCycle: cycleCount > 0,
          bridgeEdgeCount: props.bridgeEdgeCount ?? 0
        }
      });
    }

    if (externalCompound) {
      const collapsed = collapsedSet.has(externalCompound.id);
      const count = externalCompound.properties?.memberCount ?? leaves.filter(l => l.parent === externalCompound.id).length;
      const extLabel = `${externalCompound.label || 'External Dependencies'} (${count})`;
      visibleNodes.push({
        data: {
          ...externalCompound,
          label: extLabel,
          fullLabel: externalCompound.label || 'External Dependencies',
          external: true,
          externalGroup: true,
          isSuperNode: collapsed,
          communityCollapsed: collapsed,
          nodeWidth: collapsed ? Math.min(240, 130 + String(extLabel).length * 4) : 140,
          memberCount: count
        }
      });
    }

    // Add leaves for expanded communities & external bucket (cap each expanded container at 160 to stay responsive)
    const perCommRendered = new Map();
    for (const leaf of leaves) {
      const parentId = leaf.parent;
      if (parentId && containerById.has(parentId) && collapsedSet.has(parentId)) {
        continue;
      }
      const seen = perCommRendered.get(parentId || '') || 0;
      if (seen >= 160) continue;
      perCommRendered.set(parentId || '', seen + 1);
      includedLeafIds.add(leaf.id);
      const deg = degreeMap.get(leaf.id) || 0;
      visibleNodes.push({
        data: {
          ...leaf,
          label: labelFn(leaf),
          fullLabel: fullLabelFn(leaf),
          nodeWidth: Math.min(152, 88 + deg * 4),
          parent: parentId && containerById.has(parentId) ? parentId : undefined,
          activationStatus: leaf.properties?.activationStatus,
          external: Boolean(leaf.origin && leaf.origin !== 'PROJECT'),
          misplaced: Boolean(leaf.properties?.misplaced),
          misplacedRationale: leaf.properties?.misplacedRationale || '',
          communityId: leaf.properties?.communityId || '',
          inCycle: Boolean(leaf.inCycle)
        }
      });
    }

    // Build edges: aggregate edges whose endpoints belong to collapsed super-nodes
    const visibleEdges = [];
    const aggMap = new Map();
    for (const edge of graph.edges) {
      const srcLeaf = leafById.get(edge.source);
      const tgtLeaf = leafById.get(edge.target);
      const srcCollapsed = srcLeaf?.parent && collapsedSet.has(srcLeaf.parent);
      const tgtCollapsed = tgtLeaf?.parent && collapsedSet.has(tgtLeaf.parent);

      const effSource = srcCollapsed ? srcLeaf.parent : edge.source;
      const effTarget = tgtCollapsed ? tgtLeaf.parent : edge.target;
      if (effSource === effTarget) continue; // Internal edge inside collapsed super-node

      if (!srcCollapsed && !includedLeafIds.has(effSource)) continue;
      if (!tgtCollapsed && !includedLeafIds.has(effTarget)) continue;

      if (srcCollapsed || tgtCollapsed) {
        const key = effSource + '->' + effTarget;
        const prev = aggMap.get(key);
        const touchesExternal = effSource === 'compound:external' || effTarget === 'compound:external'
          || srcLeaf?.parent === 'compound:external' || tgtLeaf?.parent === 'compound:external'
          || Boolean(srcLeaf?.origin && srcLeaf.origin !== 'PROJECT')
          || Boolean(tgtLeaf?.origin && tgtLeaf.origin !== 'PROJECT');
        const edgeIsBridge = !touchesExternal
          && Boolean(edge.properties?.isBridge ?? (srcLeaf?.parent && tgtLeaf?.parent && srcLeaf.parent !== tgtLeaf.parent));
        if (!prev) {
          aggMap.set(key, {
            id: 'agg:' + key,
            source: effSource,
            target: effTarget,
            kind: touchesExternal ? 'external.dependency' : (edgeIsBridge ? 'community.bridge' : 'community.dependency'),
            classification: edge.classification || 'MUST',
            isBridge: edgeIsBridge,
            inCycle: Boolean(edge.inCycle),
            supportCount: Number(edge.supportCount || 1),
            edgeCount: 1,
            preliminary: Boolean(preliminaryGraph)
          });
        } else {
          prev.edgeCount += 1;
          prev.supportCount += Number(edge.supportCount || 1);
          if (edge.inCycle) prev.inCycle = true;
          if (edgeIsBridge && !touchesExternal) {
            prev.isBridge = true;
            prev.kind = 'community.bridge';
          }
        }
      } else if (visibleEdges.length < 900) {
        visibleEdges.push({
          data: {
            ...edge,
            isBridge: Boolean(edge.properties?.isBridge),
            inCycle: Boolean(edge.inCycle),
            preliminary: Boolean(preliminaryGraph || edge.derivation === 'SYNTAX_IMPORT_PREVIEW')
          }
        });
      }
    }

    for (const agg of aggMap.values()) {
      const unit = agg.isBridge ? 'bridge' : 'dep';
      visibleEdges.push({
        data: {
          ...agg,
          label: `${agg.edgeCount} ${unit}${agg.edgeCount === 1 ? '' : 's'}`
        }
      });
    }

    let collapsedCommunityCount = 0;
    for (const c of compounds) {
      if (collapsedSet.has(c.id)) collapsedCommunityCount++;
    }

    return {
      elements: [...visibleNodes, ...visibleEdges],
      visibleLeafCount: includedLeafIds.size,
      collapsedCommunityCount,
      totalCommunityCount: compounds.length,
      hasExternalGroup: Boolean(externalCompound),
      externalCollapsed: Boolean(externalCompound && collapsedSet.has(externalCompound.id)),
      visibleEdgeCount: visibleEdges.length
    };
  }

  /**
   * Renders Emergent Leiden Communities, Package-vs-Community Drift ("Misplaced Types"),
   * and Cross-Community Bridges inside the Review -> Insights view.
   */
  function renderCommunitiesInsights(container, data, { scope, openCommunityInMap, inspectSymbol }) {
    const communities = Array.isArray(data?.communities) ? data.communities : [];
    const misplaced = Array.isArray(data?.misplacedTypes) ? data.misplacedTypes : [];
    const bridges = Array.isArray(data?.communityBridges) ? data.communityBridges : [];
    if (!communities.length && !misplaced.length && !bridges.length) return;

    function makeTable(headers, rows) {
      const tr = append(el('tr'), ...headers.map(h => Object.assign(el('th', '', h), { scope: 'col' })));
      return append(el('div', 'table-scroll'), append(el('table', 'comparison-table insights-table'),
        append(el('thead'), tr), append(el('tbody'), ...rows)));
    }

    // 1. Emergent Communities Card
    if (communities.length) {
      const card = el('section', 'card');
      const head = append(el('div', 'section-header'),
        append(el('div'),
          el('h2', '', `Emergent Leiden Communities (${communities.length})`),
          el('p', 'small muted', `Pure-Java Traag et al. (2019) Leiden clustering · Modularity Q = ${Number(data.communityModularity ?? 0).toFixed(4)} · deterministic member-set SHA-256 IDs.`)),
        openCommunityInMap ? btn('Open Community Super-Nodes Map →', () => openCommunityInMap(null, scope), 'button primary') : null
      );
      card.append(head);
      card.append(makeTable(
        ['Community', 'Dominant Package', 'Members', 'Cohesion', 'Misplaced', 'Bridges', 'Map'],
        communities.slice(0, 30).map(c => append(el('tr'),
          el('td', '', c.label || c.id),
          el('td', '', c.dominantPackage || '—'),
          el('td', '', c.memberCount ?? 0),
          el('td', '', `${Math.round(Number(c.cohesion ?? 0) * 100)}%`),
          el('td', '', c.misplacedCount ?? 0),
          el('td', '', c.bridgeEdgeCount ?? 0),
          append(el('td'), openCommunityInMap ? btn('Focus in map', () => openCommunityInMap(c.hubIdentity, scope), 'text-button') : el('span', '', '—'))
        ))
      ));
      container.append(card);
    }

    // 2. Package-vs-Community Drift ("Misplaced Types")
    const driftCard = el('section', 'card');
    driftCard.append(
      el('h2', '', `Architectural Drift — Misplaced Types (${misplaced.length})`),
      el('p', 'small muted', 'Types declared in one package whose structural dependencies predominantly pull them into another emergent Leiden community.')
    );
    if (!misplaced.length) {
      driftCard.append(el('p', 'small muted', 'No package-vs-community drift detected in this scope.'));
    } else {
      driftCard.append(makeTable(
        ['Type', 'Declared Package', 'Emergent Community', 'Comm / Pkg Edges', 'Action'],
        misplaced.slice(0, 25).map(m => append(el('tr'),
          append(el('td'), el('strong', '', m.label || m.identity), el('p', 'small muted', m.rationale || '')),
          el('td', '', m.declaredPackage || '—'),
          el('td', '', m.communityLabel || m.communityId),
          el('td', '', `${m.internalCommunityEdges} / ${m.declaredPackageEdges}`),
          append(el('td'), openCommunityInMap ? btn('Show in map', () => openCommunityInMap(m.identity, scope), 'text-button') : null)
        ))
      ));
    }
    container.append(driftCard);

    // 3. Cross-Community Bridges
    if (bridges.length) {
      const bridgeCard = el('section', 'card');
      bridgeCard.append(
        el('h2', '', `Cross-Community Bridges (${bridges.length})`),
        el('p', 'small muted', 'Structural couplings spanning distinct Leiden communities.')
      );
      bridgeCard.append(makeTable(
        ['From Community', 'To Community', 'Edges', 'Sample Couplings'],
        bridges.slice(0, 20).map(b => append(el('tr'),
          el('td', '', b.fromCommunityLabel || b.fromCommunityId),
          el('td', '', b.toCommunityLabel || b.toCommunityId),
          el('td', '', b.edgeCount ?? 0),
          el('td', 'small', Array.isArray(b.sampleEdges) ? b.sampleEdges.slice(0, 3).join('; ') : '—')
        ))
      ));
      container.append(bridgeCard);
    }
  }

  /**
   * Inline Markdown section reader backed by POST /api/v1/graphs/{graph}/markdown-section
   */
  async function loadMarkdownSectionInto(host, api, graphId, path, heading, signal) {
    host.replaceChildren(el('p', 'small muted', 'Reading verified Markdown section…'));
    try {
      const body = heading ? { path, heading } : { path };
      const data = await api(`/graphs/${graphPath(graphId)}/markdown-section`, body, signal);
      host.replaceChildren();
      if (!data?.found) {
        host.append(el('p', 'small muted', `Section not found in ${path}.`));
        return;
      }
      if (data.mode === 'HEADINGS') {
        const list = el('div', 'evo-advisory-filters');
        for (const h of (data.headings || []).slice(0, 20)) {
          list.append(btn(`L${h.startLine}: ${h.title}`, () => loadMarkdownSectionInto(host, api, graphId, path, h.title, signal), 'evo-layer-pill'));
        }
        host.append(el('p', 'small muted', `Select a heading in ${data.path}:`), list);
        return;
      }
      const box = el('div', 'evo-md-section-box');
      const meta = append(el('div', 'evo-md-section-meta'),
        el('strong', '', `${data.path} · L${data.startLine}–${data.endLine}`),
        data.heading ? el('span', '', `§ ${data.heading}`) : null,
        data.digest ? el('code', '', String(data.digest).slice(0, 19) + '…') : null,
        data.redactedSecretsCount > 0 ? el('span', 'evo-adv-badge', `${data.redactedSecretsCount} secret(s) redacted`) : null
      );
      const pre = el('pre', 'evo-md-section-pre', data.numberedSource || data.content || '');
      box.append(meta, pre);
      host.append(box);
    } catch (err) {
      host.replaceChildren(el('p', 'small muted', `Could not read Markdown section: ${err.message}`));
    }
  }

  /**
   * Appends a quarantined "Docs, SQL & IaC Links (Advisory)" card to the Entity Inspector.
   */
  async function attachInspectorAdvisoryLinks(body, api, graphId, entityQuery, signal) {
    if (!body || !graphId) return;
    const card = el('section', 'evo-advisory-inspector-card');
    const head = append(el('div', 'evo-advisory-inspector-head'),
      el('strong', '', 'Docs, SQL & IaC Links (Advisory)'),
      el('span', 'evo-adv-badge', 'ADVISORY / UNVERIFIED')
    );
    const status = el('p', 'small muted', 'Checking quarantined non-code overlay…');
    card.append(head, status);
    body.append(card);

    try {
      const qs = entityQuery ? `?entity=${encodeURIComponent(entityQuery)}&limit=30` : '?limit=20';
      const data = await api(`/graphs/${graphPath(graphId)}/advisory-links${qs}`, undefined, signal, 'GET');
      if (signal?.aborted) return;
      const links = Array.isArray(data?.links) ? data.links : [];
      const nodes = Array.isArray(data?.nodes) ? data.nodes : [];
      if (!links.length && !nodes.length) {
        status.textContent = 'No advisory Markdown/ADR, SQL DDL <-> JPA, Terraform, or MCP links matched this entity.';
        return;
      }
      status.textContent = `Quarantined (${data.schema || 'advisory.doc-link:v1'} · ${data.derivation || 'INFERRED'}): excluded from canonical digest, policy verdicts & Score v2.`;
      const list = el('ul', 'evo-advisory-list');
      const sectionHost = el('div');

      for (const l of links.slice(0, 15)) {
        const li = el('li', 'evo-advisory-item');
        const main = append(el('div', 'evo-advisory-item-main'),
          el('span', 'evo-advisory-kind', `${l.kind} · ${l.matchBasis || 'deterministic'}`),
          el('span', '', `${l.sourceLabel} → ${l.targetLabel}`),
          el('span', 'small muted', `${l.sourcePath}:${l.startLine}${l.endLine > l.startLine ? '-' + l.endLine : ''}`)
        );
        li.append(main);
        if (String(l.sourcePath || '').toLowerCase().endsWith('.md') || String(l.sourcePath || '').toLowerCase().endsWith('.mdx')) {
          const headingMatch = String(l.sourceLabel || '').includes('§ ') ? String(l.sourceLabel).split('§ ')[1] : '';
          li.append(btn('Read section', () => loadMarkdownSectionInto(sectionHost, api, graphId, l.sourcePath, headingMatch, signal), 'button subtle'));
        }
        list.append(li);
      }
      card.append(list, sectionHost);
    } catch (err) {
      if (!signal?.aborted) status.textContent = 'Advisory overlay unavailable: ' + err.message;
    }
  }

  /**
   * Full Architecture -> "Docs, SQL & IaC (Advisory)" tab view.
   */
  async function renderAdvisoryView(env, api) {
    const root = el('section', 'card');
    const titleRow = append(el('div', 'section-header'),
      append(el('div'),
        el('h2', '', 'Docs, SQL DDL <-> JPA, Terraform & MCP Links'),
        el('p', 'small muted', '100% deterministic pure-Java extraction over captured CAS bytes (zero LLM, zero host execution).')
      ),
      el('span', 'evo-adv-badge', 'ADVISORY / UNVERIFIED')
    );
    const banner = append(el('div', 'evo-advisory-banner'),
      el('span', '', 'Quarantine Contract: schema advisory.doc-link:v1 (INFERRED / ADVISORY_UNVERIFIED). Adding or modifying .md, .sql, .tf, or .mcp.json changes 0 bits of canonical KnowledgeGraph digests, Policy verdicts, or Score v2.')
    );

    const searchInput = el('input');
    searchInput.type = 'search';
    searchInput.placeholder = 'Filter by table, JPA entity, ADR, Terraform resource, path…';
    searchInput.setAttribute('aria-label', 'Filter advisory links');

    let activeKind = '';
    const filterBar = el('div', 'evo-advisory-filters');
    const kindDefs = [
      ['', 'All'],
      ['SQL', 'SQL ↔ JPA'],
      ['DOC', 'Docs & ADRs'],
      ['TF_', 'Terraform / HCL'],
      ['MCP_', 'MCP Configs (Env Names Only)'],
      ['JS_', 'JS/TS Aliases']
    ];
    const resultsHost = el('div');
    const sectionHost = el('div');

    async function refresh() {
      resultsHost.replaceChildren(el('p', 'small muted', 'Loading quarantined advisory overlay…'));
      try {
        const params = new URLSearchParams();
        if (searchInput.value.trim()) params.set('entity', searchInput.value.trim());
        if (activeKind) params.set('kind', activeKind);
        params.set('limit', '120');
        const data = await api(`/graphs/${graphPath(env.graph)}/advisory-links?${params.toString()}`, undefined, env.signal, 'GET');
        if (env.signal.aborted) return;
        resultsHost.replaceChildren();
        const stats = append(el('div', 'stats'),
          append(el('div', 'stat'), el('span', '', 'Advisory Nodes'), el('strong', '', data.totalNodes ?? 0)),
          append(el('div', 'stat'), el('span', '', 'Advisory Links'), el('strong', '', data.totalLinks ?? 0)),
          append(el('div', 'stat'), el('span', '', 'Parse-Time Redacted Secrets'), el('strong', '', data.redactedSecretsCount ?? 0)),
          append(el('div', 'stat'), el('span', '', 'Trust Classification'), el('strong', '', data.trust || 'ADVISORY_UNVERIFIED'))
        );
        resultsHost.append(stats);

        const links = Array.isArray(data.links) ? data.links : [];
        const nodes = Array.isArray(data.nodes) ? data.nodes : [];

        if (links.length) {
          const list = el('ul', 'evo-advisory-list');
          for (const l of links) {
            const li = el('li', 'evo-advisory-item');
            const main = append(el('div', 'evo-advisory-item-main'),
              el('span', 'evo-advisory-kind', `${l.kind} · ${l.matchBasis}`),
              el('strong', '', `${l.sourceLabel} → ${l.targetLabel} (${l.targetKind})`),
              el('span', 'small muted', `${l.sourcePath}:${l.startLine}${l.endLine > l.startLine ? '-' + l.endLine : ''}`)
            );
            li.append(main);
            if (String(l.sourcePath || '').toLowerCase().endsWith('.md') || String(l.sourcePath || '').toLowerCase().endsWith('.mdx')) {
              const heading = String(l.sourceLabel || '').includes('§ ') ? String(l.sourceLabel).split('§ ')[1] : '';
              li.append(btn('Read section', () => loadMarkdownSectionInto(sectionHost, api, env.graph, l.sourcePath, heading, env.signal), 'button subtle'));
            }
            list.append(li);
          }
          resultsHost.append(el('h3', '', `Extracted Links (${links.length}${data.linksTruncated ? '+' : ''})`), list);
        }

        if (nodes.length) {
          const nodeList = el('ul', 'evo-advisory-list');
          for (const n of nodes.slice(0, 60)) {
            const li = el('li', 'evo-advisory-item');
            const main = append(el('div', 'evo-advisory-item-main'),
              el('span', 'evo-advisory-kind', n.kind),
              el('strong', '', n.label),
              el('span', 'small muted', `${n.sourcePath}:${n.startLine}${n.endLine > n.startLine ? '-' + n.endLine : ''}`)
            );
            li.append(main);
            if (n.kind === 'MD_HEADING' || n.kind === 'MD_DOCUMENT' || n.kind === 'ADR_DOCUMENT') {
              li.append(btn('Read section', () => loadMarkdownSectionInto(sectionHost, api, env.graph, n.sourcePath, n.kind === 'MD_HEADING' ? n.label : '', env.signal), 'button subtle'));
            }
            nodeList.append(li);
          }
          resultsHost.append(el('h3', '', `Extracted Artifact Nodes (${nodes.length}${data.nodesTruncated ? '+' : ''})`), nodeList);
        }

        if (!links.length && !nodes.length) {
          resultsHost.append(el('p', 'small muted', 'No advisory Markdown/ADR, SQL DDL, Terraform, JS alias, or MCP config artifacts matched the current filter.'));
        }
      } catch (err) {
        if (!env.signal.aborted) resultsHost.replaceChildren(el('p', 'small muted', 'Could not load advisory links: ' + err.message));
      }
    }

    for (const [k, label] of kindDefs) {
      const chip = btn(label, () => {
        activeKind = k;
        for (const ch of filterBar.querySelectorAll('button')) ch.classList.toggle('is-active', ch.dataset.kind === k);
        void refresh();
      }, 'evo-layer-pill' + (k === '' ? ' is-active' : ''));
      chip.dataset.kind = k;
      filterBar.append(chip);
    }
    searchInput.addEventListener('input', () => void refresh());
    filterBar.append(searchInput);

    root.append(titleRow, banner, filterBar, sectionHost, resultsHost);
    void refresh();
    return root;
  }

  /**
   * Resolves a relative Markdown link target against the current Wiki page path
   * (e.g. from "index.md" -> "communities/order.md", or from "communities/a.md" -> "../index.md").
   */
  function resolveWikiRelativePath(currentPagePath, href) {
    const clean = String(href || '').split('#')[0].split('?')[0].trim();
    if (!clean) return '';
    const baseParts = String(currentPagePath || '').split('/').slice(0, -1);
    const segs = clean.startsWith('/') ? clean.slice(1).split('/') : [...baseParts, ...clean.split('/')];
    const stack = [];
    for (const s of segs) {
      if (!s || s === '.') continue;
      if (s === '..') {
        if (stack.length) stack.pop();
      } else {
        stack.push(s);
      }
    }
    return stack.join('/');
  }

  /**
   * Strict zero-XSS DOM inline Markdown renderer (bold, italic, inline code, clickable internal Wiki links,
   * and validated https?:// external links). Never uses innerHTML.
   */
  function appendSafeInlineMarkdown(parent, text, currentPagePath, onNavigatePage) {
    const str = String(text || '');
    const pattern = /(`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*|(?<![A-Za-z0-9_])_[^_\n]+_(?![A-Za-z0-9_])|\[[^\]\n]+\]\([^)\s]+\))/g;
    let cursor = 0;
    for (const match of str.matchAll(pattern)) {
      if (match.index > cursor) {
        parent.append(document.createTextNode(str.slice(cursor, match.index)));
      }
      const token = match[0];
      if (token.startsWith('`')) {
        parent.append(el('code', 'evo-wiki-code', token.slice(1, -1)));
      } else if (token.startsWith('**')) {
        parent.append(el('strong', '', token.slice(2, -2)));
      } else if (token.startsWith('*') || token.startsWith('_')) {
        parent.append(el('em', '', token.slice(1, -1)));
      } else if (token.startsWith('[')) {
        const parts = token.match(/^\[([^\]]+)\]\(([^)\s]+)\)$/);
        if (parts) {
          const linkText = parts[1];
          const href = parts[2].trim();
          if (/^https?:\/\//i.test(href)) {
            const a = el('a', 'evo-wiki-ext-link', linkText);
            a.href = href;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
            parent.append(a);
          } else if (href.startsWith('#') && href.length > 1) {
            const slug = href.slice(1).toLowerCase();
            const b = btn(linkText, () => {
              const article = parent.closest('.evo-wiki-md');
              const target = article?.querySelector(`[data-heading-slug="${CSS.escape(slug)}"]`);
              target?.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }, 'evo-wiki-inline-link');
            parent.append(b);
          } else if (!href.startsWith('//') && !/^[a-z][a-z0-9+.-]*:/i.test(href) && typeof onNavigatePage === 'function') {
            const targetPath = resolveWikiRelativePath(currentPagePath, href);
            if (targetPath) {
              const b = btn(linkText, () => onNavigatePage(targetPath), 'evo-wiki-inline-link');
              b.dataset.wikiTarget = targetPath;
              parent.append(b);
            } else {
              parent.append(document.createTextNode(linkText));
            }
          } else {
            parent.append(document.createTextNode(linkText));
          }
        } else {
          parent.append(document.createTextNode(token));
        }
      }
      cursor = match.index + token.length;
    }
    if (cursor < str.length) {
      parent.append(document.createTextNode(str.slice(cursor)));
    }
  }

  function splitMdTableRow(line) {
    let trimmed = line.trim();
    if (trimmed.startsWith('|')) trimmed = trimmed.slice(1);
    if (trimmed.endsWith('|')) trimmed = trimmed.slice(0, -1);
    const cells = [];
    let cur = '';
    let inCode = false;
    for (let i = 0; i < trimmed.length; i++) {
      const ch = trimmed[i];
      if (ch === '`') {
        inCode = !inCode;
        cur += ch;
      } else if (!inCode && ch === '\\' && trimmed[i + 1] === '|') {
        cur += '|';
        i++;
      } else if (!inCode && ch === '|') {
        cells.push(cur.trim());
        cur = '';
      } else {
        cur += ch;
      }
    }
    cells.push(cur.trim());
    return cells;
  }

  /**
   * Strict zero-XSS DOM block Markdown renderer for the Architecture Wiki modal.
   * Renders headings, tables, fenced code blocks, blockquotes, lists, and clickable Wiki links.
   */
  function renderSafeWikiMarkdown(mdText, currentPagePath, onNavigatePage) {
    const root = el('article', 'evo-wiki-md');
    const lines = String(mdText || '').replace(/\r\n?/g, '\n').split('\n');
    let i = 0;
    while (i < lines.length) {
      const line = lines[i];
      const trimmed = line.trim();
      if (!trimmed || /^<!--[\s\S]*-->$/.test(trimmed)) {
        i++;
        continue;
      }
      // Fenced code block
      if (trimmed.startsWith('```')) {
        const lang = trimmed.slice(3).trim();
        const codeLines = [];
        i++;
        while (i < lines.length && !lines[i].trim().startsWith('```')) {
          codeLines.push(lines[i]);
          i++;
        }
        if (i < lines.length) i++;
        const pre = el('pre', 'evo-code-block');
        const code = el('code', lang ? 'lang-' + lang.replace(/[^a-zA-Z0-9_-]/g, '') : '', codeLines.join('\n'));
        pre.append(code);
        root.append(pre);
        continue;
      }
      // Headings #..######
      const hm = trimmed.match(/^(#{1,6})\s+(.+)$/);
      if (hm) {
        const lvl = Math.min(6, hm[1].length);
        const h = el('h' + lvl, 'evo-wiki-h' + lvl);
        h.dataset.headingSlug = hm[2].toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
        appendSafeInlineMarkdown(h, hm[2], currentPagePath, onNavigatePage);
        root.append(h);
        i++;
        continue;
      }
      // Horizontal rule
      if (/^[-*_]{3,}$/.test(trimmed)) {
        root.append(el('hr', 'evo-wiki-hr'));
        i++;
        continue;
      }
      // Blockquote
      if (trimmed.startsWith('>')) {
        const bq = el('blockquote', 'evo-wiki-quote');
        const parts = [];
        while (i < lines.length && lines[i].trim().startsWith('>')) {
          parts.push(lines[i].trim().replace(/^>\s?/, ''));
          i++;
        }
        const p = el('p', '');
        appendSafeInlineMarkdown(p, parts.join(' '), currentPagePath, onNavigatePage);
        bq.append(p);
        root.append(bq);
        continue;
      }
      // Markdown Table
      if (trimmed.includes('|') && i + 1 < lines.length && /^\s*\|?[\s:-]+\|[\s|:-]*$/.test(lines[i + 1].trim())) {
        const headers = splitMdTableRow(line);
        i += 2;
        const tableWrap = el('div', 'evo-wiki-table-wrap');
        const table = el('table', 'evo-wiki-table');
        const thead = el('thead');
        const htr = el('tr');
        for (const hc of headers) {
          const th = el('th');
          appendSafeInlineMarkdown(th, hc, currentPagePath, onNavigatePage);
          htr.append(th);
        }
        thead.append(htr);
        const tbody = el('tbody');
        while (i < lines.length && lines[i].trim().includes('|') && !lines[i].trim().startsWith('#')) {
          const rowCells = splitMdTableRow(lines[i]);
          const tr = el('tr');
          for (let ci = 0; ci < Math.max(headers.length, rowCells.length); ci++) {
            const td = el('td');
            appendSafeInlineMarkdown(td, rowCells[ci] || '', currentPagePath, onNavigatePage);
            tr.append(td);
          }
          tbody.append(tr);
          i++;
        }
        table.append(thead, tbody);
        tableWrap.append(table);
        root.append(tableWrap);
        continue;
      }
      // Unordered list
      if (/^[-*]\s+/.test(trimmed)) {
        const ul = el('ul', 'evo-wiki-list');
        while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
          const li = el('li');
          appendSafeInlineMarkdown(li, lines[i].trim().replace(/^[-*]\s+/, ''), currentPagePath, onNavigatePage);
          ul.append(li);
          i++;
        }
        root.append(ul);
        continue;
      }
      // Ordered list
      if (/^\d+\.\s+/.test(trimmed)) {
        const ol = el('ol', 'evo-wiki-list');
        while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
          const li = el('li');
          appendSafeInlineMarkdown(li, lines[i].trim().replace(/^\d+\.\s+/, ''), currentPagePath, onNavigatePage);
          ol.append(li);
          i++;
        }
        root.append(ol);
        continue;
      }
      // Paragraph
      const pLines = [];
      while (i < lines.length) {
        const cur = lines[i].trim();
        if (!cur || cur.startsWith('#') || cur.startsWith('```') || cur.startsWith('>')
            || /^[-*]\s+/.test(cur) || /^\d+\.\s+/.test(cur) || /^[-*_]{3,}$/.test(cur)) {
          break;
        }
        pLines.push(cur);
        i++;
      }
      const p = el('p', 'evo-wiki-p');
      appendSafeInlineMarkdown(p, pLines.join(' '), currentPagePath, onNavigatePage);
      root.append(p);
    }
    return root;
  }

  /**
   * Opens the interactive Multi-Page Architecture Wiki / Dossier preview & download modal.
   */
  async function openWikiDossierModal(api, graphId, contextId) {
    const dialog = document.getElementById('wiki-dossier-dialog');
    const body = document.getElementById('wiki-dossier-body');
    if (!dialog || !body) return;
    if (!graphId) {
      body.replaceChildren(el('p', 'muted', 'Select an analyzed repository first to generate its Architecture Wiki & Dossier.'));
      if (!dialog.open) dialog.showModal();
      return;
    }
    body.replaceChildren(el('p', 'muted', 'Generating deterministic multi-page Architecture Wiki & Dossier…'));
    if (!dialog.open) dialog.showModal();

    const qs = new URLSearchParams();
    if (contextId) qs.set('context', contextId);
    qs.set('format', 'json');
    try {
      const data = await api(`/graphs/${graphPath(graphId)}/wiki-dossier?${qs.toString()}`, undefined, undefined, 'GET');
      const pages = Array.isArray(data?.pages) ? data.pages : [];
      const zipQs = new URLSearchParams(contextId ? { context: contextId, format: 'zip' } : { format: 'zip' });
      const mdQs = new URLSearchParams(contextId ? { context: contextId, format: 'markdown' } : { format: 'markdown' });

      const toolbar = append(el('div', 'evo-advisory-filters'),
        append(el('span', 'small muted', `${pages.length} pages · ${data.totalCommunities ?? 0} Leiden communities · SHA-256 ${String(data.bundleSha256 || '').slice(0, 19)}…`)),
        (() => {
          const a = el('a', 'button primary', 'Download Wiki (.zip)');
          a.href = `/api/v1/graphs/${graphPath(graphId)}/wiki-dossier?${zipQs.toString()}`;
          a.download = 'evo-architecture-wiki.zip';
          return a;
        })(),
        (() => {
          const a = el('a', 'button subtle', 'Download Combined Dossier (.md)');
          a.href = `/api/v1/graphs/${graphPath(graphId)}/wiki-dossier?${mdQs.toString()}`;
          a.download = 'evo-architecture-dossier.md';
          return a;
        })()
      );

      const nav = el('nav', 'evo-wiki-nav');
      nav.setAttribute('aria-label', 'Wiki pages');
      const preview = el('div', 'evo-wiki-preview');
      const pageIndexByPath = new Map(pages.map((pg, idx) => [pg.path, idx]));

      const navigateToPath = targetPath => {
        const foundIdx = pageIndexByPath.get(targetPath);
        if (foundIdx != null && pages[foundIdx]) {
          showPage(pages[foundIdx], foundIdx);
          preview.scrollTop = 0;
        }
      };

      const showPage = (p, idx) => {
        for (const [bi, b] of [...nav.children].entries()) b.classList.toggle('is-active', bi === idx);
        preview.replaceChildren(
          append(el('div', 'evo-md-section-meta'),
            el('strong', '', p.path),
            el('span', '', `${p.bytes ?? 0} bytes`),
            p.sha256 ? el('code', '', String(p.sha256).slice(0, 19) + '…') : null),
          renderSafeWikiMarkdown(p.content || '', p.path, navigateToPath)
        );
      };
      pages.forEach((p, idx) => {
        const b = btn(`${p.title} (${p.path})`, () => showPage(p, idx), 'evo-wiki-nav-btn');
        nav.append(b);
      });
      if (pages.length) showPage(pages[0], 0);
      body.replaceChildren(toolbar, append(el('div', 'evo-wiki-grid'), nav, preview));
    } catch (err) {
      body.replaceChildren(el('p', 'muted', 'Could not generate Wiki Dossier: ' + err.message));
    }
  }

  /**
   * Opens the IDE / MCP Quick-Connect modal showing copy-pasteable configs with resolved paths,
   * 1-click Scoped PAT helper (in multi-user/authenticated mode), and all 6 live MCP Resources.
   */
  async function openMcpSetupModal(api) {
    const dialog = document.getElementById('mcp-setup-dialog');
    const body = document.getElementById('mcp-setup-body');
    if (!dialog || !body) return;
    body.replaceChildren(el('p', 'muted', 'Loading MCP setup configuration…'));
    if (!dialog.open) dialog.showModal();
    try {
      const data = await api('/mcp/setup', undefined, undefined, 'GET');
      const proxyConfigObj = data.workbenchProxyConfig || {};
      const proxyJson = JSON.stringify(proxyConfigObj, null, 2);
      const directJson = JSON.stringify(data.directOfflineConfig || {}, null, 2);
      const toolCount = Number(data.toolCount || 19);

      const copyBlock = (title, note, text) => {
        const pre = el('pre', 'evo-code-block', text);
        const copyBtn = btn('Copy JSON', async () => {
          try { await navigator.clipboard.writeText(pre.textContent || text); copyBtn.textContent = 'Copied!'; setTimeout(() => { copyBtn.textContent = 'Copy JSON'; }, 1200); } catch (_) {}
        }, 'button subtle');
        const section = append(el('section', 'card'),
          append(el('div', 'evo-advisory-inspector-head'), el('h3', '', title), copyBtn),
          el('p', 'small muted', note),
          pre
        );
        return { section, pre, copyBtn };
      };

      const proxyBlock = copyBlock('1. Live Workbench Proxy (Claude Code / Cursor / Codex / Antigravity)',
        data.multiUserMode
          ? 'Multi-user mode is active: mint a read-only scoped PAT below (EVO_MCP_TOKEN, sent as Authorization: Bearer).'
          : `Connects directly to this running Workbench on loopback port ${data.workbenchPort}.`,
        proxyJson);

      const directBlock = copyBlock('2. Direct Local Store (100% Offline, Zero Daemon, Auto-Bound Default Graph)',
        'Reads published graphs, persisted jobs, and policy reports straight from disk without requiring a running Workbench server.',
        directJson);

      // 1-Click Scoped PAT helper when authenticated & account store supports PATs
      let patCard = null;
      const isSignedIn = Boolean(window.EvoGitHubAuth?.isAuthenticated?.());
      if (data.multiUserMode || (data.patMintSupported && isSignedIn)) {
        patCard = el('section', 'card evo-pat-mcp-card');
        const revealHost = el('div', 'evo-pat-minted-host');
        const patErr = el('p', 'small muted');
        const mintBtn = btn('1-Click Mint Read-Only MCP Token (mcp:read)', async () => {
          mintBtn.disabled = true;
          patErr.textContent = 'Minting scoped PAT…';
          try {
            const minted = await api('/auth/tokens', {
              label: 'IDE MCP Bridge',
              scopes: ['mcp:read'],
              expiresInDays: 90
            }, undefined, 'POST');
            const tok = String(minted?.token || '');
            const hydratedConfig = JSON.parse(JSON.stringify(proxyConfigObj));
            const srv = hydratedConfig?.mcpServers?.['evo-workbench'];
            if (srv) {
              if (!Array.isArray(srv.args)) srv.args = [];
              if (!srv.args.includes('--auth-token-env')) {
                srv.args.push('--auth-token-env', 'EVO_MCP_TOKEN');
              }
              if (!srv.env) srv.env = {};
              srv.env.EVO_MCP_TOKEN = tok;
            }
            const hydratedJson = JSON.stringify(hydratedConfig, null, 2);
            const copyTokBtn = btn('Copy Token', async () => {
              try { await navigator.clipboard.writeText(tok); copyTokBtn.textContent = 'Copied!'; setTimeout(() => { copyTokBtn.textContent = 'Copy Token'; }, 1200); } catch (_) {}
            }, 'button primary');
            const copyCfgBtn = btn('Copy Proxy Config with Token', async () => {
              try { await navigator.clipboard.writeText(hydratedJson); copyCfgBtn.textContent = 'Copied!'; setTimeout(() => { copyCfgBtn.textContent = 'Copy Proxy Config with Token'; }, 1200); } catch (_) {}
            }, 'button subtle');
            const reveal = el('div', 'evo-pat-reveal-banner');
            reveal.id = 'mcp-pat-reveal';
            reveal.append(
              el('strong', '', 'Scoped PAT minted (shown once — never persisted in plaintext)'),
              append(el('div', 'evo-pat-reveal-row'), el('code', 'evo-pat-secret-code', tok), copyTokBtn, copyCfgBtn)
            );
            revealHost.replaceChildren(reveal);
            patErr.textContent = '';
          } catch (err) {
            patErr.textContent = err.message || 'Sign in first to mint a Personal Access Token.';
          } finally {
            mintBtn.disabled = false;
          }
        }, 'button primary');
        mintBtn.id = 'mcp-mint-pat-btn';
        const manageBtn = btn('Manage Tokens in Account Settings →', () => {
          dialog.close();
          window.EvoGitHubAuth?.openWorkspaceAccountModal?.('tokens');
        }, 'button subtle');
        patCard.append(
          append(el('div', 'evo-advisory-inspector-head'),
            el('h3', '', 'Scoped MCP Personal Access Token'),
            manageBtn),
          el('p', 'small muted', 'Mints a read-only mcp:read token (SHA-256 hash stored in SQLite). Revoke anytime in Account Settings.'),
          append(el('div', 'rs-action-row'), mintBtn),
          patErr,
          revealHost
        );
        dialog.addEventListener('close', () => { revealHost.replaceChildren(); }, { once: true });
      }

      const resList = el('ul', 'evo-advisory-list');
      for (const r of (data.resources || [])) {
        resList.append(append(el('li', 'evo-advisory-item'),
          append(el('div', 'evo-advisory-item-main'),
            el('span', 'evo-advisory-kind', r.uri),
            el('span', '', r.description))
        ));
      }

      const ruleBlock = copyBlock('Recommended Agent Rule (always_on, non-blocking)',
        'Guides coding agents to query Evo MCP first without brittle PreToolUse hard blocks.',
        data.agentRuleSnippet || '');

      body.replaceChildren(
        el('p', 'small muted', `MCP Protocol ${data.protocolVersion || '2025-11-25'} · ${toolCount} read-only tools + 6 MCP Resources · ${data.multiUserMode ? 'Multi-User Scoped PAT Auth Enabled' : 'Local Single-User Loopback Mode'}`),
        patCard,
        proxyBlock.section,
        directBlock.section,
        append(el('section', 'card'),
          el('h3', '', '6 Built-in MCP Resources'),
          resList),
        ruleBlock.section
      );
    } catch (err) {
      body.replaceChildren(el('p', 'muted', 'Could not load MCP setup: ' + err.message));
    }
  }

  /**
   * Renders Leiden PR Community-Overlap Risk inside GitHub Hub PR Diff results.
   */
  function renderPrCommunityOverlapCard(overlap) {
    if (!overlap || !overlap.touchedCommunityCount) return null;
    const risk = String(overlap.riskLevel || 'LOW').toUpperCase();
    const riskCls = risk === 'HIGH' ? 'is-high' : risk === 'MEDIUM' ? 'is-medium' : 'is-low';
    const card = el('section', 'evo-pr-community-card');
    card.append(
      append(el('div', 'evo-pr-community-head'),
        el('strong', '', `Leiden Community Overlap Risk (${overlap.touchedCommunityCount} touched)`),
        el('span', 'evo-risk-pill ' + riskCls, risk + ' RISK')
      ),
      el('p', 'small muted', overlap.summary || '')
    );
    if (Array.isArray(overlap.touchedCommunities) && overlap.touchedCommunities.length) {
      const list = el('ul', 'evo-advisory-list');
      for (const tc of overlap.touchedCommunities.slice(0, 8)) {
        list.append(append(el('li', 'evo-advisory-item'),
          append(el('div', 'evo-advisory-item-main'),
            el('strong', '', tc.label || tc.communityId),
            el('span', 'small muted', `${tc.dominantPackage || '—'} · cohesion ${Math.round(Number(tc.cohesion || 0) * 100)}% · ${(tc.changedFiles || []).length} changed file(s)`))
        ));
      }
      card.append(list);
    }
    if (Array.isArray(overlap.peerPrOverlaps) && overlap.peerPrOverlaps.length) {
      card.append(el('h4', '', 'Semantic Peer-PR Overlaps'));
      const peers = el('ul', 'evo-advisory-list');
      for (const po of overlap.peerPrOverlaps.slice(0, 6)) {
        peers.append(append(el('li', 'evo-advisory-item'),
          append(el('div', 'evo-advisory-item-main'),
            el('strong', '', `PR #${po.peerPullNumber}: ${po.peerTitle || ''}`),
            el('span', 'small muted', `Shared: ${(po.sharedCommunityLabels || []).join(', ')}` + (po.semanticOnlyOverlap ? ' · Semantic-only conflict (0 overlapping file paths)' : '')))
        ));
      }
      card.append(peers);
    }
    return card;
  }

  /**
   * Renders pairwise Leiden Community Evolution (SURVIVED / SPLIT / MERGED / BORN / DISSOLVED)
   * inside Snapshot Comparison.
   */
  function renderCommunityEvolutionCard(report) {
    if (!report) return null;
    const transitions = Array.isArray(report.transitions) ? report.transitions : [];
    const stabilityPct = Math.round(Number(report.stabilityIndex ?? 0) * 100);
    const wrap = el('div', 'evo-comm-evo-body');
    const summary = append(el('div', 'stats'),
      append(el('div', 'stat'), el('span', '', 'Stability Index'), el('strong', '', `${stabilityPct}%`)),
      append(el('div', 'stat'), el('span', '', 'Survived'), el('strong', '', report.survivedCount ?? 0)),
      append(el('div', 'stat'), el('span', '', 'Split / Merged'), el('strong', '', `${report.splitCount ?? 0} / ${report.mergedCount ?? 0}`)),
      append(el('div', 'stat'), el('span', '', 'Born / Dissolved'), el('strong', '', `${report.bornCount ?? 0} / ${report.dissolvedCount ?? 0}`))
    );
    wrap.append(summary);
    if (!transitions.length) {
      wrap.append(el('p', 'small muted', 'No Leiden communities detected in either snapshot context.'));
      return wrap;
    }
    const list = el('ul', 'evo-advisory-list');
    for (const tr of transitions.slice(0, 30)) {
      const status = String(tr.status || 'SURVIVED').toUpperCase();
      const cls = status === 'SURVIVED' ? 'is-low' : (status === 'SPLIT' || status === 'MERGED') ? 'is-medium' : 'is-high';
      const beforeLbl = Array.isArray(tr.beforeLabels) && tr.beforeLabels.length ? tr.beforeLabels.join(', ') : '—';
      const afterLbl = Array.isArray(tr.afterLabels) && tr.afterLabels.length ? tr.afterLabels.join(', ') : '—';
      list.append(append(el('li', 'evo-advisory-item'),
        append(el('div', 'evo-advisory-item-main'),
          el('strong', '', `${beforeLbl} → ${afterLbl}`),
          el('span', 'small muted', tr.summary || `${tr.sharedMembers ?? 0} shared · Jaccard ${Number(tr.jaccard ?? 0).toFixed(2)}`)),
        el('span', 'evo-risk-pill ' + cls, status)
      ));
    }
    wrap.append(list);
    return wrap;
  }

  window.EvoCommunityAdvisory = {
    graphPath,
    exportCytoscapePng,
    buildCytoscapeSvg,
    exportCytoscapeSvg,
    buildCommunityElements,
    renderCommunitiesInsights,
    attachInspectorAdvisoryLinks,
    renderAdvisoryView,
    openWikiDossierModal,
    openMcpSetupModal,
    renderPrCommunityOverlapCard,
    renderCommunityEvolutionCard,
    loadMarkdownSectionInto
  };
})();
