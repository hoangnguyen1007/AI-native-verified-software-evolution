(() => {
  'use strict';
  const sessions = new Map();
  const prompts = {
    EXPLORE: 'What does this snapshot establish about the architecture, and what remains uncertain?',
    DESIGN: 'Which design changes are worth considering here? Explain the trade-offs and how to test them.',
    REPAIR: 'What is the smallest architecture change to address this finding, and how should I verify it?'
  };
  const taskTitles = {EXPLORE:'Understand the structure',DESIGN:'Review a design choice',REPAIR:'Plan a repair'};
  const make = (tag, cls, text) => {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = String(text);
    return node;
  };
  const button = (text, action, style = 'button') => {
    const node = make('button',style,text);
    node.type = 'button';
    node.addEventListener('click',action);
    return node;
  };
  const label = (text, control) => {
    const node = make('label','architecture-chat-field');
    node.append(make('span','',text),control);
    return node;
  };

  const toolMeta = {
    search_symbols: { label: 'Search Symbols', tone: 'is-graph' },
    inspect_symbol: { label: 'Inspect Symbol', tone: 'is-graph' },
    inspect_symbol_details: { label: 'Symbol Details', tone: 'is-graph' },
    trace_path: { label: 'Trace Path', tone: 'is-graph' },
    analyze_impact: { label: 'Blast Radius', tone: 'is-graph' },
    impact_analysis: { label: 'Blast Radius', tone: 'is-graph' },
    get_architecture_insights: { label: 'Architecture Insights', tone: 'is-graph' },
    get_architecture_score: { label: 'Architecture Score v2', tone: 'is-graph' },
    architecture_brief: { label: 'Architecture Brief', tone: 'is-graph' },
    get_repo_map: { label: 'RepoMap (PPR)', tone: 'is-map' },
    inspect_advisory_links: { label: 'Advisory Links', tone: 'is-map' },
    list_findings: { label: 'Policy Findings', tone: 'is-spring' },
    inspect_spring_wiring: { label: 'Spring Wiring', tone: 'is-spring' },
    inspect_spring_conditions: { label: 'Spring Conditions', tone: 'is-spring' },
    inspect_connections: { label: 'Connections', tone: 'is-graph' },
    inspect_codeowners: { label: 'CODEOWNERS', tone: 'is-spring' },
    inspect_gaps: { label: 'Capability Gaps', tone: 'is-spring' },
    list_captured_files: { label: 'Captured Files', tone: 'is-source' },
    search_captured_source: { label: 'Search Source', tone: 'is-source' },
    read_captured_source: { label: 'Read Source', tone: 'is-source' },
    read_markdown_section: { label: 'Read Markdown Section', tone: 'is-source' }
  };
  let mermaidMarkerSeq = 0;
  function formatToolName(raw) {
    return toolMeta[raw]?.label || String(raw || 'tool').replaceAll('_', ' ');
  }
  function toolTone(raw) {
    return toolMeta[raw]?.tone || 'is-graph';
  }
  function formatCompactTokens(value) {
    const n = Math.max(0, Math.round(Number(value) || 0));
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M';
    if (n >= 1_000) return (n / 1_000).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(n);
  }
  function renderUsageBadge(result) {
    const usage = result?.model?.usage || result?.promptCacheMetrics || result?.model?.promptCacheMetrics;
    if (!usage || typeof usage !== 'object') return null;
    const input = Math.max(0, Number(usage.inputTokens) || 0);
    const output = Math.max(0, Number(usage.outputTokens) || 0);
    const cacheRead = Math.max(0, Number(usage.cacheReadTokens) || 0);
    const cacheCreate = Math.max(0, Number(usage.cacheCreationTokens) || 0);
    if (input === 0 && output === 0 && cacheRead === 0 && cacheCreate === 0) return null;
    const totalIn = Math.max(input, cacheRead);
    const rawPct = usage.estimatedCacheSavingsPct != null && Number.isFinite(Number(usage.estimatedCacheSavingsPct))
      ? Number(usage.estimatedCacheSavingsPct)
      : (totalIn > 0 && cacheRead > 0 ? Math.min(100, (cacheRead / totalIn) * 100) : 0);
    const pct = Math.round(rawPct);
    const outPart = output > 0 ? ' · ' + formatCompactTokens(output) + ' out' : '';
    let labelText;
    let tone = '';
    if (cacheRead > 0) {
      labelText = `Cache ${pct}% · ${formatCompactTokens(cacheRead)} read / ${formatCompactTokens(totalIn)} in${outPart}`;
      tone = ' is-cache-hit';
    } else if (cacheCreate > 0) {
      labelText = `Cache warm · ${formatCompactTokens(cacheCreate)} cached / ${formatCompactTokens(totalIn)} in${outPart}`;
      tone = ' is-cache-warm';
    } else {
      labelText = `Tokens · ${formatCompactTokens(totalIn)} in / ${formatCompactTokens(output)} out`;
    }
    const badge = make('span', 'architecture-chat-usage-badge' + tone, labelText);
    badge.title = `Prompt token economy — Input: ${input.toLocaleString()}, Output: ${output.toLocaleString()}, Cache read: ${cacheRead.toLocaleString()}, Cache write: ${cacheCreate.toLocaleString()}, Estimated cache savings: ${rawPct.toFixed(1)}%`;
    return badge;
  }

  function appendCitationIds(parent, raw, onCite) {
    const segs = window.EvoModel?.parseCitations
      ? window.EvoModel.parseCitations(raw)
      : (() => {
          const inner = raw.slice(1, -1);
          const ids = [];
          for (const part of inner.split(',')) {
            const t = part.trim();
            const r = t.match(/^E(\d+)\s*[–-]\s*E?(\d+)$/i);
            if (r) {
              const a = Number(r[1]), b = Number(r[2]);
              for (let n = a; n <= b && n - a <= 25; n++) ids.push('E' + n);
            } else {
              const m = t.match(/^E?(\d+)$/i);
              if (m) ids.push('E' + Number(m[1]));
            }
          }
          return ids.length ? [{ type: 'citations', ids: [...new Set(ids)] }] : [{ type: 'text', value: raw }];
        })();
    for (const seg of segs) {
      if (seg.type !== 'citations') {
        parent.append(document.createTextNode(seg.value));
        continue;
      }
      if (typeof onCite !== 'function') {
        parent.append(document.createTextNode(raw));
        continue;
      }
      const group = seg.ids.length > 1 ? make('span', 'architecture-chat-cite-group') : parent;
      for (const citeId of seg.ids) {
        const pill = button('[' + citeId + ']', () => onCite(citeId), 'architecture-chat-inline-cite');
        pill.title = 'Open evidence ' + citeId;
        group.append(pill);
      }
      if (group !== parent) parent.append(group);
    }
  }

  function inlineMarkdown(parent, text, onCite) {
    const pattern = /(`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*|\[[^\]\n]+\]\(https?:\/\/[^\s)]+\)|\[E\d+(?:\s*(?:,|–|-)\s*E?\d+)*\])/g;
    let cursor = 0;
    for (const match of text.matchAll(pattern)) {
      parent.append(document.createTextNode(text.slice(cursor,match.index)));
      const value = match[0];
      if (/^\[E\d+(?:\s*(?:,|–|-)\s*E?\d+)*\]$/.test(value)) {
        appendCitationIds(parent, value, onCite);
      } else if (value.startsWith('[')) {
        const parts = value.match(/^\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)$/);
        const link = make('a','',parts[1]);
        link.href = parts[2];
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        parent.append(link);
      } else {
        const strong = value.startsWith('**');
        const element = make(value.startsWith('`') ? 'code' : strong ? 'strong' : 'em','',
          value.slice(strong ? 2 : 1,strong ? -2 : -1));
        parent.append(element);
      }
      cursor = match.index + value.length;
    }
    parent.append(document.createTextNode(text.slice(cursor)));
  }

  function svgEl(tag, attrs = {}) {
    const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, String(v));
    return el;
  }

  function tryRenderMermaid(sourceText) {
    const lines = sourceText.split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('%%'));
    if (!lines.length || !/^(?:graph|flowchart)\s+(TD|TB|LR|RL|BT)\b/i.test(lines[0])) return null;
    const dir = lines[0].match(/^(?:graph|flowchart)\s+(\w+)/i)[1].toUpperCase();
    const horizontal = dir === 'LR' || dir === 'RL';
    const nodes = new Map();
    const edges = [];
    const ensureNode = (rawId, rawLabel) => {
      const id = rawId.trim();
      if (!id) return;
      if (!nodes.has(id)) nodes.set(id, { id, label: (rawLabel || id).replace(/^["']|["']$/g, '').trim() });
      else if (rawLabel) nodes.get(id).label = rawLabel.replace(/^["']|["']$/g, '').trim();
    };
    const parseEndpoint = token => {
      const m = token.trim().match(/^([A-Za-z0-9_.:-]+)(?:\[\[?([^\]]+)\]\]?|\(([^)]+)\)|\{([^}]+)\})?$/);
      if (!m) return null;
      const lbl = m[2] || m[3] || m[4] || '';
      ensureNode(m[1], lbl);
      return m[1];
    };
    for (let i = 1; i < lines.length; i++) {
      const l = lines[i];
      if (/^(?:subgraph|end|classDef|class|style|linkStyle|click)\b/i.test(l)) continue;
      const e = l.match(/^(.+?)\s*(?:--+>|==+>|-.+->)\s*(?:\|([^|]+)\|)?\s*(.+?)$/);
      if (e) {
        const from = parseEndpoint(e[1]);
        const to = parseEndpoint(e[3]);
        if (from && to) edges.push({ from, to, label: (e[2] || '').trim() });
      } else {
        parseEndpoint(l);
      }
    }
    if (nodes.size < 2 || nodes.size > 24 || edges.length === 0) return null;

    // Topological / BFS layering
    const indeg = new Map([...nodes.keys()].map(id => [id, 0]));
    const adj = new Map([...nodes.keys()].map(id => [id, []]));
    edges.forEach(e => {
      adj.get(e.from)?.push(e.to);
      indeg.set(e.to, (indeg.get(e.to) || 0) + 1);
    });
    const layerOf = new Map();
    const q = [...indeg.entries()].filter(([, d]) => d === 0).map(([id]) => id);
    if (!q.length) q.push([...nodes.keys()][0]);
    q.forEach(id => layerOf.set(id, 0));
    while (q.length) {
      const cur = q.shift();
      const nextLayer = (layerOf.get(cur) || 0) + 1;
      for (const nxt of adj.get(cur) || []) {
        if (!layerOf.has(nxt) || (layerOf.get(nxt) < nextLayer && nextLayer < nodes.size)) {
          layerOf.set(nxt, nextLayer);
          q.push(nxt);
        }
      }
    }
    for (const id of nodes.keys()) if (!layerOf.has(id)) layerOf.set(id, 0);

    const layers = new Map();
    for (const [id, lay] of layerOf.entries()) {
      if (!layers.has(lay)) layers.set(lay, []);
      layers.get(lay).push(id);
    }
    const nodeW = 152, nodeH = 40, gapX = horizontal ? 64 : 26, gapY = horizontal ? 24 : 52;
    const pos = new Map();
    let maxW = 320, maxH = 160;
    const orderedLayers = [...layers.keys()].sort((a, b) => a - b);
    orderedLayers.forEach((lay, li) => {
      const col = layers.get(lay);
      col.forEach((id, idx) => {
        const x = horizontal ? 20 + li * (nodeW + gapX) : 20 + idx * (nodeW + gapX);
        const y = horizontal ? 20 + idx * (nodeH + gapY) : 20 + li * (nodeH + gapY);
        pos.set(id, { x, y });
        maxW = Math.max(maxW, x + nodeW + 24);
        maxH = Math.max(maxH, y + nodeH + 24);
      });
    });

    const wrap = make('div', 'architecture-chat-mermaid-shell');
    const svg = svgEl('svg', { viewBox: `0 0 ${maxW} ${maxH}`, class: 'architecture-chat-mermaid-svg', role: 'img', 'aria-label': 'Architecture flow diagram' });
    const markerId = 'evo-mermaid-arrow-' + (++mermaidMarkerSeq);
    const defs = svgEl('defs');
    const marker = svgEl('marker', { id: markerId, markerWidth: 8, markerHeight: 8, refX: 7, refY: 4, orient: 'auto' });
    marker.append(svgEl('path', { d: 'M0,0 L8,4 L0,8 Z', class: 'mermaid-arrow-head' }));
    defs.append(marker);
    svg.append(defs);

    edges.forEach(e => {
      const a = pos.get(e.from), b = pos.get(e.to);
      if (!a || !b) return;
      const x1 = horizontal ? a.x + nodeW : a.x + nodeW / 2;
      const y1 = horizontal ? a.y + nodeH / 2 : a.y + nodeH;
      const x2 = horizontal ? b.x : b.x + nodeW / 2;
      const y2 = horizontal ? b.y + nodeH / 2 : b.y;
      const cx = (x1 + x2) / 2, cy = (y1 + y2) / 2;
      const d = horizontal ? `M${x1},${y1} C${cx},${y1} ${cx},${y2} ${x2},${y2}` : `M${x1},${y1} C${x1},${cy} ${x2},${cy} ${x2},${y2}`;
      svg.append(svgEl('path', { d, class: 'mermaid-edge-path', 'marker-end': `url(#${markerId})` }));
      if (e.label) {
        const txt = svgEl('text', { x: cx, y: cy - 4, 'text-anchor': 'middle', class: 'mermaid-edge-label' });
        txt.textContent = e.label.slice(0, 22);
        svg.append(txt);
      }
    });

    for (const [id, info] of nodes.entries()) {
      const p = pos.get(id);
      const g = svgEl('g', { class: 'mermaid-node-group' });
      g.append(svgEl('rect', { x: p.x, y: p.y, width: nodeW, height: nodeH, rx: 8, class: 'mermaid-node-rect' }));
      const label = info.label.length > 21 ? info.label.slice(0, 20) + '…' : info.label;
      const t = svgEl('text', { x: p.x + nodeW / 2, y: p.y + nodeH / 2 + 4, 'text-anchor': 'middle', class: 'mermaid-node-label' });
      t.textContent = label;
      g.append(t);
      svg.append(g);
    }

    const src = make('details', 'architecture-chat-raw');
    src.append(make('summary', '', 'Mermaid source'), make('pre', '', sourceText));
    wrap.append(svg, src);
    return wrap;
  }

  function renderCodeBlock(container, lang, codeLines) {
    const rawCode = codeLines.join('\n');
    const cleanLang = (lang || '').trim();
    if (cleanLang.toLowerCase() === 'mermaid') {
      const diagram = tryRenderMermaid(rawCode);
      if (diagram) { container.append(diagram); return; }
    }
    const shell = make('div', 'architecture-chat-code-shell');
    const bar = make('div', 'architecture-chat-code-bar');
    bar.append(make('span', 'architecture-chat-code-lang', cleanLang || 'code'));
    const copyBtn = button('Copy', async () => {
      try {
        await navigator.clipboard?.writeText(rawCode);
        if (window.EvoCopyFx?.flash) window.EvoCopyFx.flash(copyBtn, 'Copied', 'Copy');
        else {
          copyBtn.textContent = 'Copied';
          setTimeout(() => { copyBtn.textContent = 'Copy'; }, 1400);
        }
      } catch (_) {
        copyBtn.textContent = 'Select manually';
      }
    }, 'architecture-chat-copy-code');
    bar.append(copyBtn);
    const pre = make('pre');
    const code = make('code');
    if (cleanLang.toLowerCase() === 'diff') {
      for (const l of codeLines) {
        const cls = l.startsWith('+') && !l.startsWith('+++') ? 'diff-line is-add'
          : l.startsWith('-') && !l.startsWith('---') ? 'diff-line is-del'
          : l.startsWith('@@') ? 'diff-line is-hunk' : 'diff-line';
        const span = make('span', cls, l || '\u00a0');
        code.append(span);
      }
    } else {
      code.textContent = rawCode;
    }
    pre.append(code);
    shell.append(bar, pre);
    container.append(shell);
  }

  function tableCells(line) {
    const row = line.trim().replace(/^\|/, '').replace(/(?<!\\)\|$/, '');
    if (!line.includes('|')) return [];
    return row.split(/(?<!\\)\|/).map(cell => cell.trim().replace(/\\\|/g,'|'));
  }

  function tableHeader(header, separator) {
    const cells = tableCells(header);
    const delimiters = tableCells(separator);
    return cells.length && cells.length === delimiters.length && delimiters.every(cell => /^:?-+:?$/.test(cell));
  }

  function appendTable(container, lines, index) {
    const header = tableCells(lines[index]);
    const alignments = tableCells(lines[index + 1]).map(cell => cell.endsWith(':')
      ? cell.startsWith(':') ? 'center' : 'right' : 'left');
    const wrapper = make('div','architecture-chat-table-scroll');
    wrapper.tabIndex = 0;
    wrapper.setAttribute('role','region');
    wrapper.setAttribute('aria-label','Markdown table');
    const table = make('table');
    const body = make('tbody');
    const addRow = (section, cells, heading, onCite) => {
      const row = make('tr');
      header.forEach((_,column) => {
        const cell = make(heading ? 'th' : 'td');
        if (heading) cell.scope = 'col';
        cell.style.textAlign = alignments[column];
        inlineMarkdown(cell,cells[column] || '',onCite);
        row.append(cell);
      });
      section.append(row);
    };
    const head = make('thead');
    addRow(head,header,true,container._onCite);
    let next = index + 2;
    while (next < lines.length && tableCells(lines[next]).length &&
        !/^\s*(?:#{1,6}\s|```|[-*+]\s|\d+\.\s|>)/.test(lines[next])) {
      addRow(body,tableCells(lines[next]),false,container._onCite);
      next++;
    }
    table.append(head,body);
    wrapper.append(table);
    container.append(wrapper);
    return next - 1;
  }

  function renderMarkdown(text, onCite) {
    const container = make('div','architecture-chat-markdown');
    container._onCite = onCite;
    const lines = text.replace(/\r\n?/g,'\n').split('\n');
    let paragraph = [], list = null, code = null, codeLines = [];
    const flush = () => {
      if (paragraph.length) {
        const node = make('p');
        inlineMarkdown(node,paragraph.join(' '),onCite);
        container.append(node);
        paragraph = [];
      }
    };
    for (let index = 0; index < lines.length; index++) {
      const line = lines[index];
      if (code !== null) {
        if (/^\s*```\s*$/.test(line)) {
          renderCodeBlock(container,code,codeLines);
          code = null; codeLines = [];
        } else codeLines.push(line);
        continue;
      }
      const fence = line.match(/^\s*```([^`]*)$/);
      if (fence) { flush(); list = null; code = fence[1]; continue; }
      if (index + 1 < lines.length && tableHeader(line,lines[index + 1])) {
        flush(); list = null; index = appendTable(container,lines,index); continue;
      }
      // Some providers put complete, pipe-delimited rows on a single line.
      if (line.trimStart().startsWith('|') && line.trimEnd().endsWith('|')) {
        const rows = line.trim().split(/(?<!\\)\|\s*\|/).map(row => row.trim());
        if (rows.length >= 3 && tableHeader(rows[0],rows[1]) &&
            rows.every(row => tableCells(row).length === tableCells(rows[0]).length)) {
          flush(); list = null; appendTable(container,rows,0); continue;
        }
      }
      if (/^\s{0,3}(?:-\s*){3,}$|^\s{0,3}(?:\*\s*){3,}$|^\s{0,3}(?:_\s*){3,}$/.test(line)) {
        flush(); list = null; container.append(make('hr')); continue;
      }
      const item = line.match(/^\s*(?:([-*+])\s+|(\d+)\.\s+)(.*)$/);
      if (item) {
        flush();
        const kind = item[1] ? 'ul' : 'ol';
        if (!list || list.tagName.toLowerCase() !== kind) { list = make(kind); container.append(list); }
        const li = make('li'); inlineMarkdown(li,item[3],onCite); list.append(li); continue;
      }
      list = null;
      const heading = line.match(/^\s{0,3}(#{1,6})\s+(.+)$/);
      if (heading) {
        flush(); const node = make('h' + Math.min(6,heading[1].length + 2));
        inlineMarkdown(node,heading[2],onCite); container.append(node); continue;
      }
      if (!line.trim()) { flush(); continue; }
      paragraph.push(line.trim());
    }
    flush();
    if (code !== null) renderCodeBlock(container,code,codeLines);
    delete container._onCite;
    return container;
  }

  window.mountEvoArchitectureChat = function mount(scope) {
    const userScope = window.EvoGitHubAuth?.getUserId?.() || 'local';
    const key = JSON.stringify([userScope, scope.snapshot || scope.graph,scope.context,scope.finding]);
    const prefix = 'evo.chat.v1.' + encodeURIComponent(key) + '.';
    const activeKey = prefix + 'active';
    const defaults = () => ({
      turns:[],mode:scope.finding ? 'REPAIR' : 'EXPLORE',focus:'',draft:'',
      agentic:Boolean(scope.agentEnabled),reviewerAgentic:false,
      includeSource:Boolean(scope.provider && (scope.finding || !scope.provider.remote))
    });
    const newId = () => crypto.randomUUID();
    let active;
    try { active = sessionStorage.getItem(activeKey); } catch (_) { /* Use the in-memory session. */ }
    active ||= sessions.get(activeKey) || newId();
    let session;
    function loadSession(id) {
      const memory = sessions.get(prefix + id);
      if (memory) return memory;
      try {
        const saved = JSON.parse(localStorage.getItem(prefix + id));
        if (saved && Array.isArray(saved.turns) && ['EXPLORE','DESIGN','REPAIR'].includes(saved.mode)
            && typeof saved.focus === 'string' && typeof saved.draft === 'string') return saved;
      } catch (_) { /* A missing or unreadable record starts a new conversation. */ }
      return defaults();
    }
    session = loadSession(active);
    if (scope.seedPrompt) session.draft = String(scope.seedPrompt).slice(0, 2400);

    const node = make('section','architecture-chat');
    const head = make('header','architecture-chat-header');
    const title = make('div');
    title.append(make('h2','','Architecture chat'),make('p','muted',scope.contextLabel));
    const fresh = button('New conversation',reset);
    const history = make('select','architecture-chat-history');
    history.setAttribute('aria-label','Conversation history');
    const headerActions = make('div','architecture-chat-header-actions');
    const exportBtn = button('Export .md', exportConversation, 'button subtle');
    const deleteBtn = button('Delete', deleteConversation, 'button subtle');
    headerActions.append(history, fresh, exportBtn, deleteBtn);
    head.append(title, headerActions);
    const pins = make('div','architecture-chat-pins');
    const snapshotKey = scope.snapshot?.split('sha256:').at(-1);
    const snapshot = make('code','',snapshotKey?.length === 64 ? 'Snapshot ' + snapshotKey.slice(0,8) : 'Captured snapshot');
    snapshot.title = scope.snapshot || scope.graph;
    pins.append(snapshot);
    if (scope.finding) {
      pins.append(button(scope.findingLabel || 'Attached finding',scope.openFinding),
        button('Detach finding',scope.clearFinding,'button subtle'));
    }
    let preNotice = null;
    if (scope.preliminary) {
      preNotice = make('div','chat-preliminary-notice');
      preNotice.setAttribute('role','status');
      const tierLabel = scope.truthTier === 'PARTIAL_CONTEXTS' ? 'Partial module analysis' : 'Preliminary structure';
      preNotice.append(make('strong','',tierLabel + ' · '),
        document.createTextNode('Chat is currently grounded in the early structural preview while deep Java semantic resolution, Spring SAT, and Architecture Health v2 finish in the background.'));
    }

    const layout = make('div','architecture-chat-layout');
    const conversation = make('div','architecture-chat-conversation');
    const log = make('div','architecture-chat-log');
    log.setAttribute('role','log');
    log.setAttribute('aria-label','Conversation');
    log.tabIndex = 0;
    const status = make('p','architecture-chat-status muted');
    status.setAttribute('role','status');
    const form = make('form','architecture-chat-form');
    const settings = make('div','architecture-chat-settings');
    const mode = make('select');
    for (const [value,text] of Object.entries({EXPLORE:'Explore', DESIGN:'Design review', REPAIR:'Plan a repair'})) {
      const option = make('option','',text);
      option.value = value;
      mode.append(option);
    }
    mode.value = session.mode;
    mode.addEventListener('change',() => { session.mode = mode.value; save(); });
    const focus = make('input');
    focus.maxLength = 256;
    focus.value = session.focus;
    focus.placeholder = 'Class, package or symbol ID';
    focus.addEventListener('input',() => { session.focus = focus.value; save(); });
    settings.append(label('Task',mode),label('Focus symbol (optional)',focus));
    const input = make('textarea');
    input.maxLength = 2400;
    input.rows = 3;
    input.required = true;
    input.placeholder = 'Ask about dependencies, design trade-offs, or a repair…';
    input.value = session.draft;
    input.addEventListener('input',() => { session.draft = input.value; save(); });
    const providerModelName = scope.provider?.model ? ' · ' + scope.provider.model : '';
    const modelState = make('span','architecture-chat-model-state',
      scope.provider ? ((scope.provider.remote ? 'Remote AI' : 'Local AI') + providerModelName) : 'Local evidence');
    modelState.dataset.probe = scope.provider ? 'idle' : 'offline';
    modelState.title = scope.provider?.model || 'Connect an AI provider to generate conversational answers.';
    pins.append(modelState);
    if (scope.provider && typeof scope.probeProvider === 'function') {
      const probeBtn = button('Check provider', async () => {
        probeBtn.disabled = true;
        probeBtn.textContent = 'Checking…';
        try {
          const probe = await scope.probeProvider(scope.signal);
          const reachable = Boolean(probe?.ok ?? probe?.reachable ?? probe?.healthy);
          modelState.dataset.probe = reachable ? 'ok' : 'fail';
          const latency = probe?.latencyMs != null ? ` (${probe.latencyMs} ms)` : '';
          modelState.textContent = (scope.provider.remote ? 'Remote AI' : 'Local AI') +
            (probe?.model ? ' · ' + probe.model : providerModelName) +
            (reachable ? ' · Ready' + latency : ' · Unreachable');
          probeBtn.textContent = reachable ? 'Provider ready' : 'Retry probe';
        } catch (_) {
          modelState.dataset.probe = 'fail';
          probeBtn.textContent = 'Retry probe';
        } finally {
          probeBtn.disabled = false;
        }
      }, 'text-button architecture-chat-probe-btn');
      pins.append(probeBtn);
    }
    const repairAction = button(scope.finding ? 'Repair this finding' : 'Choose a finding to repair', async () => {
      repairAction.disabled = true;
      try { await (scope.finding ? scope.openRepair() : scope.chooseFinding()); }
      catch (error) { if (!scope.signal.aborted) status.textContent = error.message; }
      finally { repairAction.disabled = false; }
    },'button subtle');
    if (scope.finding ? scope.openRepair : scope.chooseFinding) pins.append(repairAction);
    const source = make('input');
    source.type = 'checkbox';
    source.name = 'includeSource';
    source.checked = session.includeSource !== undefined
      ? Boolean(session.includeSource)
      : Boolean(scope.provider && (scope.finding || !scope.provider.remote));
    source.addEventListener('change', () => { session.includeSource = source.checked; save(); });
    const sourceOption = make('label','architecture-chat-consent');
    sourceOption.append(source,make('span','', scope.finding
      ? 'Include captured source code for this finding'
      : 'Include captured source files in AI context & tools'));
    if (scope.provider?.remote) modelState.title = 'Questions, chat history and selected evidence are sent to ' + scope.provider.endpoint;
    const controls = make('div','architecture-chat-controls');
    const agentToggleInput = make('input');
    agentToggleInput.type = 'checkbox';
    agentToggleInput.name = 'agentic';
    agentToggleInput.checked = session.agentic !== false && Boolean(scope.agentEnabled);
    agentToggleInput.addEventListener('change', () => { session.agentic = agentToggleInput.checked; save(); });
    const agentToggleLabel = make('label','architecture-chat-agent-toggle');
    agentToggleLabel.title = 'Allow the AI assistant to inspect RepoMap, symbols, source files, Spring conditions, impact, and insights via read-only graph tools.';
    agentToggleLabel.append(agentToggleInput, make('span','','Agent · Graph tools'));
    const reviewerAgenticInput = make('input');
    reviewerAgenticInput.type = 'checkbox';
    reviewerAgenticInput.name = 'reviewerAgentic';
    reviewerAgenticInput.checked = Boolean(session.reviewerAgentic && scope.agentEnabled);
    reviewerAgenticInput.addEventListener('change', () => { session.reviewerAgentic = reviewerAgenticInput.checked; save(); });
    const reviewerAgenticLabel = make('label','architecture-chat-agent-toggle');
    reviewerAgenticLabel.title = 'Opt-in: allow the second-pass architecture reviewer to run independent graph and source tools before critiquing the answer.';
    reviewerAgenticLabel.append(reviewerAgenticInput, make('span','','Reviewer · Agentic tools'));
    const stop = button('Stop waiting',() => pending?.abort(),'button subtle');
    stop.hidden = true;
    const submit = make('button','button primary',scope.provider ? 'Send question' : 'Read evidence');
    submit.type = 'submit';
    if (scope.agentEnabled && scope.provider) controls.append(agentToggleLabel, reviewerAgenticLabel);
    controls.append(stop,submit);
    const contextOptions = make('details','architecture-chat-context-options');
    contextOptions.append(make('summary','','Task and symbol focus'),settings);
    form.append(label('Question',input),contextOptions);
    if (scope.provider) form.append(sourceOption);
    form.append(controls);
    const evidence = make('aside','architecture-chat-evidence');
    evidence.setAttribute('aria-label','Captured architecture evidence');
    conversation.append(log,status,form);
    layout.append(conversation,evidence);
    node.append(head,pins);
    if (preNotice) node.append(preNotice);
    node.append(layout);
    let pending = null;
    const reviewSlots = new WeakMap();
    const activeReviewControllers = new Set();
    scope.signal?.addEventListener('abort', () => {
      for (const c of activeReviewControllers) c.abort();
      activeReviewControllers.clear();
    }, { once: true });

    function syncSessionControls() {
      mode.value = session.mode;
      focus.value = session.focus;
      input.value = session.draft;
      source.checked = session.includeSource !== undefined
        ? Boolean(session.includeSource)
        : Boolean(scope.provider && (scope.finding || !scope.provider.remote));
      agentToggleInput.checked = session.agentic !== false && Boolean(scope.agentEnabled);
      reviewerAgenticInput.checked = Boolean(session.reviewerAgentic && scope.agentEnabled);
    }

    function save() {
      sessions.set(prefix + active,session);
      sessions.set(activeKey,active);
      let stored = true;
      try {
        localStorage.setItem(prefix + active,JSON.stringify(session));
        sessionStorage.setItem(activeKey,active);
      } catch (_) {
        stored = false;
        status.textContent = 'Browser storage is unavailable or full. This conversation is only kept in this page.';
      }
      refreshHistory();
      return stored;
    }

    function refreshHistory() {
      const records = new Map();
      for (const [name,value] of sessions) if (name.startsWith(prefix) && name !== activeKey) records.set(name.slice(prefix.length),value);
      try {
        for (let i=0;i<localStorage.length;i++) {
          const name=localStorage.key(i);
          if (name?.startsWith(prefix)) {
            const id=name.slice(prefix.length);
            if (!records.has(id)) records.set(id,loadSession(id));
          }
        }
      } catch (_) { /* In-memory records are still selectable. */ }
      records.set(active,session);
      history.replaceChildren();
      const ordered = [...records.entries()].sort((a,b) => (b[1].updated || 0) - (a[1].updated || 0));
      for (const [id,value] of ordered) {
        const option=make('option','',value.title || value.turns?.[0]?.question?.slice(0,65) || 'New conversation');
        option.value=id;
        history.append(option);
      }
      history.value=active;
    }

    history.addEventListener('change',() => {
      if (pending) return;
      active=history.value;
      session=loadSession(active);
      syncSessionControls();
      save();
      renderConversation();
      status.textContent='Conversation restored.';
    });

    function startQuestion(task, customText = null) {
      mode.value = session.mode = task;
      input.value = session.draft = customText || prompts[task];
      save();
      input.focus();
    }

    function renderRecord(details,row) {
      const data = row.data || {};
      const summary = row.kind === 'SUMMARY' && data.counts?.[0];
      if (summary) {
        const counts = make('dl','architecture-chat-counts');
        for (const [kind,label] of [['MODULE','Modules'],['PACKAGE','Packages'],['TYPE','Types']]) {
          if (summary.nodeCounts?.[kind] !== undefined)
            counts.append(make('dt','',label),make('dd','',summary.nodeCounts[kind].toLocaleString()));
        }
        details.append(make('p','',(data.status || '') + ' · ' + Number(data.gapCount || 0).toLocaleString() + ' reported gaps'));
        if (counts.childElementCount) details.append(counts);
      }
      const rows = data.matches || data.modules || data.edges;
      if (rows) {
        const list = make('ul','architecture-chat-evidence-list');
        for (const item of rows) {
          const li = make('li');
          if (item?.path && typeof scope.openSource === 'function') {
            const loc = item.path + (item.line ? ':' + item.line : '');
            li.append(button(loc + (item.text ? ' — ' + String(item.text).slice(0, 72) : ''), () => scope.openSource({
              path: item.path,
              startLine: Math.max(1, Number(item.line || 1) - 10),
              endLine: Number(item.line || 1) + 40,
              highlightLine: item.line || 1
            }), 'text-button'));
          } else {
            li.textContent = item.name || item.label || item.target || item.path || '';
          }
          list.append(li);
        }
        details.append(list.childElementCount ? list : make('p','muted','No matching records in this sample.'));
      }
      if (row.kind === 'FINDING') {
        details.append(make('p','',(data.classification || '') + ' · ' + (data.status || '')));
        if (scope.openFinding) details.append(button('Open finding',scope.openFinding));
      }
      if (row.kind === 'SOURCE' && typeof scope.openSource === 'function') {
        const actionRow = make('div', 'architecture-chat-links');
        const labelText = data.path ? 'Open source (' + data.path + ')' : 'Open source evidence';
        actionRow.append(button(labelText, () => scope.openSource(data)));
        const ghEnt = window.EvoGitHubEnterprise;
        const permUrl = ghEnt?.permalinkFor?.(data.path, data.startLine || data.line, data.endLine, data.permalink);
        const permLink = ghEnt?.makePermalinkAnchor?.(permUrl, 'View on GitHub ↗', 'gh-permalink-link');
        if (permLink) actionRow.append(permLink);
        const owners = Array.isArray(data.owners) && data.owners.length
          ? data.owners
          : (data.path ? ghEnt?.ownersForPath?.(scope.graph, data.path) : []);
        const ownerPills = ghEnt?.makeOwnerPills?.(owners);
        if (ownerPills) actionRow.append(ownerPills);
        details.append(actionRow);
      }
      const raw = make('details','architecture-chat-raw');
      raw.append(make('summary','','Evidence data'),make('pre','',JSON.stringify(data,null,2)));
      details.append(raw);
    }

    function renderEvidence(turn, selected) {
      const heading = make('header','architecture-chat-evidence-header');
      heading.append(make('h3','','Evidence'));
      evidence.replaceChildren(heading);
      if (!turn) {
        evidence.append(make('p','muted','Open cited records here after a reply, or read the evidence directly.'));
        return;
      }
      const result = turn.result || {};
      const evidenceRows = Array.isArray(result.evidence) ? result.evidence : [];
      const digest = make('code','architecture-chat-digest',window.EvoModel.shortDigest(result.evidenceIdentity));
      digest.title = result.evidenceIdentity || '';
      if (evidenceRows.length) heading.append(digest);
      for (const row of evidenceRows) {
        const details = make('details','architecture-chat-record');
        details.open = row.id === selected || (!selected && row === evidenceRows[0]);
        const recordTitle = make('summary');
        recordTitle.append(make('span','architecture-chat-ref',row.id),make('span','',row.label));
        details.append(recordTitle);
        renderRecord(details,row);
        evidence.append(details);
        if (row.id === selected) {
          details.firstElementChild.focus({preventScroll:true});
          details.scrollIntoView({block:'nearest'});
        }
      }
      if (!evidenceRows.length) evidence.append(make('p','muted','Ask an architecture question to see snapshot evidence here.'));
      if (result.model) {
        const receipt = make('details','architecture-chat-record');
        receipt.append(make('summary','','Model receipt'),make('pre','',JSON.stringify(result.model,null,2)));
        evidence.append(receipt);
      }
    }

    function buildReviewDetails(reviewResult) {
      const review = make('details','architecture-chat-review');
      const reviewOnCite = id => {
        const row = (reviewResult.evidence || []).find(item => item.id === id);
        if (!row) return;
        renderEvidence({result:reviewResult}, id);
        if (row.kind === 'SOURCE' && row.data?.path && typeof scope.openSource === 'function') scope.openSource(row.data);
      };
      review.append(make('summary','',reviewResult.agentTrace?.length
        ? 'Architecture reviewer · agentic second pass (' + reviewResult.agentTrace.length + ' tools)'
        : 'Architecture reviewer · second model call'),
        renderMarkdown(reviewResult.answer || 'The reviewer returned no answer.', reviewOnCite));
      if (Array.isArray(reviewResult.agentTrace) && reviewResult.agentTrace.length) {
        const rTrace = make('details','architecture-chat-trace');
        rTrace.append(make('summary','','Reviewer tool steps (' + reviewResult.agentTrace.length + ')'));
        const rList = make('ol','architecture-chat-trace-list');
        for (const step of reviewResult.agentTrace) {
          const item = make('li','architecture-chat-trace-item');
          const toolPill = make('span','architecture-chat-trace-tool ' + toolTone(step.tool), formatToolName(step.tool));
          toolPill.title = step.tool || '';
          item.append(toolPill, make('span','',step.summary || ''));
          if (typeof step.durationMs === 'number') item.append(make('span','architecture-chat-trace-ms',step.durationMs + ' ms'));
          rList.append(item);
        }
        rTrace.append(rList);
        review.append(rTrace);
      }
      const links = make('div','architecture-chat-links');
      for (const id of reviewResult.citations || []) {
        const row = (reviewResult.evidence || []).find(item => item.id === id);
        if (row) links.append(button(id + ' · ' + row.label,() => renderEvidence({result:reviewResult},id),'button subtle'));
      }
      if (links.childElementCount) review.append(links);
      if (reviewResult.model) {
        const reviewUsage = renderUsageBadge(reviewResult);
        if (reviewUsage) {
          const rFooter = make('div', 'architecture-chat-turn-footer');
          rFooter.append(reviewUsage);
          review.append(rFooter);
        }
        const receipt = make('details','architecture-chat-raw');
        receipt.append(make('summary','','Reviewer model receipt'),make('pre','',JSON.stringify(reviewResult.model,null,2)));
        review.append(receipt);
      }
      if (reviewResult.limitations?.length) {
        const reviewLimits = make('ul','architecture-chat-limitations');
        reviewResult.limitations.forEach(value => reviewLimits.append(make('li','',value)));
        review.append(reviewLimits);
      }
      return review;
    }

    function appendTurn(turn) {
      const question = make('article','architecture-chat-message architecture-chat-user');
      question.append(make('h3','','You'),make('p','architecture-chat-copy',turn.question));
      const result = turn.result || {};
      const evidenceRows = Array.isArray(result.evidence) ? result.evidence : [];
      const citations = Array.isArray(result.citations) ? result.citations : [];
      const limitations = Array.isArray(result.limitations) ? result.limitations : [];
      const reply = make('article','architecture-chat-message architecture-chat-assistant');
      const labels = {ADVICE:'Architecture advice', NEEDS_EVIDENCE:'More evidence needed',
        UNVERIFIED_ADVICE:'Unverified model suggestion',EVIDENCE_ONLY:'Captured evidence',OUT_OF_SCOPE:'Outside architecture scope'};
      if (result.status === 'OUT_OF_SCOPE') reply.classList.add('architecture-chat-redirect');
      const msgHead = make('div','architecture-chat-message-head');
      msgHead.append(make('h3','',labels[result.status] || 'Architecture response'));
      if (result.answer) {
        const copyReply = button('Copy', async () => {
          try {
            await navigator.clipboard?.writeText(result.answer);
            if (window.EvoCopyFx?.flash) window.EvoCopyFx.flash(copyReply, 'Copied', 'Copy');
            else {
              copyReply.textContent = 'Copied';
              setTimeout(() => { copyReply.textContent = 'Copy'; }, 1400);
            }
          } catch (_) { copyReply.textContent = 'Failed'; }
        }, 'architecture-chat-copy-msg');
        msgHead.append(copyReply);
      }
      const onCite = id => {
        const row = evidenceRows.find(item => item.id === id);
        if (!row) return;
        renderEvidence(turn, id);
        if (row.kind === 'SOURCE' && row.data?.path && typeof scope.openSource === 'function') {
          scope.openSource(row.data);
        }
      };
      reply.append(msgHead,
        renderMarkdown(result.answer || 'The evidence panel shows the records retrieved from this snapshot.', onCite));
      if (Array.isArray(result.agentTrace) && result.agentTrace.length) {
        const traceDetails = make('details','architecture-chat-trace');
        traceDetails.append(make('summary','','Investigation steps (' + result.agentTrace.length + ')'));
        const list = make('ol','architecture-chat-trace-list');
        for (const step of result.agentTrace) {
          const item = make('li','architecture-chat-trace-item');
          const toolPill = make('span','architecture-chat-trace-tool ' + toolTone(step.tool), formatToolName(step.tool));
          toolPill.title = step.tool + (step.arguments ? ' ' + JSON.stringify(step.arguments) : '');
          item.append(toolPill);
          item.append(make('span','',step.summary || ''));
          if (Array.isArray(step.evidenceIds) && step.evidenceIds.length) {
            for (const eid of step.evidenceIds) {
              const eBtn = button('[' + eid + ']', () => onCite(eid), 'architecture-chat-inline-cite');
              eBtn.title = 'Open evidence ' + eid;
              item.append(eBtn);
            }
          }
          if (typeof step.durationMs === 'number') {
            item.append(make('span','architecture-chat-trace-ms',step.durationMs + ' ms'));
          }
          list.append(item);
        }
        traceDetails.append(list);
        reply.append(traceDetails);
      }
      const references = make('div','architecture-chat-links');
      for (const id of citations) {
        const row = evidenceRows.find(item => item.id === id);
        if (row) references.append(button(id + ' · ' + row.label,() => renderEvidence(turn,id),'button subtle'));
      }
      if (scope.openRepair && result.status !== 'OUT_OF_SCOPE' && !(result.status === 'EVIDENCE_ONLY' && result.answer))
        references.append(button('Open repair workflow',() => repairAction.click()));
      if (references.childElementCount) reply.append(references);
      const reviewSlot = make('div','architecture-chat-review-slot');
      if (turn.review) {
        reviewSlot.append(buildReviewDetails(turn.review));
      } else if (turn.reviewError) {
        reviewSlot.append(make('p','architecture-chat-limitations',turn.reviewError));
      } else if (turn._reviewPending) {
        reviewSlot.append(make('span','architecture-chat-review-pending','Reviewer checking in background…'));
      }
      reply.append(reviewSlot);
      reviewSlots.set(turn, reviewSlot);
      if (limitations.length) {
        const list = make('ul','architecture-chat-limitations');
        limitations.forEach(text => list.append(make('li','',text)));
        reply.append(list);
      }
      const usageBadge = renderUsageBadge(result);
      if (usageBadge) {
        const footer = make('div', 'architecture-chat-turn-footer');
        footer.append(usageBadge);
        reply.append(footer);
      }
      log.append(question,reply);
    }

    function renderConversation() {
      log.replaceChildren();
      if (!session.turns.length) {
        const empty = make('article','architecture-chat-welcome');
        empty.append(make('h3','','What would you like to understand?'),
          make('p','muted',scope.finding ? 'Trace the attached finding and outline a change.' :
            'Explore dependencies, test a design idea, or plan a repair from this snapshot.'));
        const choices = make('div','architecture-chat-starters');
        for (const [task,title] of Object.entries(taskTitles)) choices.append(button(title,() => startQuestion(task),'button'));
        const quickPrompts = scope.finding
          ? [
              ['Explain root cause & blast radius', 'REPAIR', 'Trace the root cause of this finding in captured source and estimate the blast radius of removing the offending dependency.'],
              ['Minimal safe fix + test plan', 'REPAIR', 'Outline the minimal code patch to resolve this finding without breaking callers, and list the tests to run in sandbox verification.']
            ]
          : [
              ['Top risks & hotspots', 'EXPLORE', 'What are the highest-risk architectural hotspots, dependency cycles, and SDP violations in this snapshot?'],
              ['How to reduce coverage gaps', 'EXPLORE', 'Explain the recorded capability gaps in this snapshot and give prioritized steps to raise analysis completeness.'],
              ['Key entrypoints (RepoMap)', 'EXPLORE', 'Summarize the main modules and highest-centrality entrypoints in this repository with source citations.']
            ];
        const quickChips = make('div','architecture-chat-quick-chips');
        for (const [chipTitle, chipTask, chipPrompt] of quickPrompts) {
          quickChips.append(button(chipTitle, () => startQuestion(chipTask, chipPrompt), 'chip'));
        }
        empty.append(choices, quickChips);
        log.append(empty);
      }
      session.turns.forEach(appendTurn);
      log.scrollTop = log.scrollHeight;
      renderEvidence(session.turns.at(-1));
    }

    function exportConversation() {
      const lines = ['# Architecture Chat', '', '- Context: `' + (scope.contextLabel || scope.context) + '`', '- Snapshot: `' + (scope.snapshot || scope.graph) + '`', ''];
      for (const t of session.turns) {
        lines.push('## Question', '', t.question, '', '## ' + (t.result?.status || 'Answer'), '', t.result?.answer || '(Evidence only)', '');
        if (t.review?.answer) lines.push('### Reviewer perspective', '', t.review.answer, '');
      }
      const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'architecture-chat-' + (scope.snapshot?.split(':').at(-1)?.slice(0, 8) || 'snapshot') + '.md';
      document.body.append(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      status.textContent = 'Conversation exported.';
    }

    function deleteConversation() {
      if (pending) return;
      sessions.delete(prefix + active);
      try { localStorage.removeItem(prefix + active); } catch (_) {}
      const remaining = [];
      for (const [k] of sessions) if (k.startsWith(prefix) && k !== activeKey) remaining.push(k.slice(prefix.length));
      try {
        for (let i = 0; i < localStorage.length; i++) {
          const k = localStorage.key(i);
          if (k?.startsWith(prefix)) {
            const id = k.slice(prefix.length);
            if (!remaining.includes(id)) remaining.push(id);
          }
        }
      } catch (_) {}
      if (remaining.length) {
        active = remaining[0];
        session = loadSession(active);
        syncSessionControls();
        save();
        renderConversation();
        status.textContent = 'Conversation deleted.';
      } else {
        reset();
      }
    }

    function reset() {
      save();
      for (const c of activeReviewControllers) c.abort();
      activeReviewControllers.clear();
      active = newId();
      session = defaults();
      session.updated = Date.now();
      syncSessionControls();
      save();
      status.textContent = 'New conversation started.';
      renderConversation();
      input.focus();
    }

    form.addEventListener('submit',async event => {
      event.preventDefault();
      const question = input.value.trim();
      if (pending || !question) return;
      const evidenceOnly = !scope.provider;
      const messages = [];
      let historyBytes = new TextEncoder().encode(question).length;
      const prior = session.turns.filter(turn => turn.result.answer && turn.result.status !== 'OUT_OF_SCOPE').slice(-7);
      for (const turn of prior.reverse()) {
        const pair = [{role:'user',content:turn.question},{role:'assistant',content:shortHistory(turn.result.answer)}];
        const pairBytes = new TextEncoder().encode(JSON.stringify(pair)).length;
        if (historyBytes + pairBytes > 96_000) break;
        messages.unshift(...pair);
        historyBytes += pairBytes;
      }
      messages.push({role:'user',content:question});
      const useAgent = Boolean(!evidenceOnly && scope.agentEnabled && agentToggleInput.checked);
      const useReviewerAgent = Boolean(!evidenceOnly && scope.agentEnabled && reviewerAgenticInput.checked);
      const query = {context:scope.context,mode:mode.value,messages,
        ...(scope.job ? {job:scope.job} : {}),
        ...(focus.value.trim() ? {focus:focus.value.trim()} : {}),
        ...(scope.finding ? {finding:scope.finding} : {}),
        ...(useAgent ? {agentic:true} : {}),
        ...(!evidenceOnly && source.checked ? {includeSource:true} : {}),
        ...(evidenceOnly ? {evidenceOnly:true} : scope.provider?.remote ? {allowRemote:true} : {})};
      pending = new AbortController();
      const request = pending;
      const signal = AbortSignal.any([scope.signal,request.signal]);
      submit.disabled = fresh.disabled = mode.disabled = focus.disabled = true;
      history.disabled = true;
      source.disabled = true;
      agentToggleInput.disabled = true;
      reviewerAgenticInput.disabled = true;
      input.readOnly = true;
      stop.hidden = false;
      status.textContent = evidenceOnly ? 'Reading snapshot evidence…' : 'Reading snapshot evidence and waiting for the model…';
      let liveBox = null, liveTimeline = null, liveBody = null, liveStage = null, liveStageLabel = null;
      let streamedText = '', rafScheduled = false;
      const ensureLiveBox = () => {
        if (liveBox) return;
        const emptyWelcome = log.querySelector('.architecture-chat-welcome');
        if (emptyWelcome) emptyWelcome.remove();
        const qEl = make('article','architecture-chat-message architecture-chat-user');
        qEl.append(make('h3','','You'),make('p','architecture-chat-copy',question));
        liveBox = make('article','architecture-chat-message architecture-chat-assistant u453-live-box');
        /* Lucian UX/UI Prompt #453 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#453 */
        liveStage = make('h3','u453-live-head');
        const star = make('span','u453-ic');
        star.setAttribute('aria-hidden','true');
        star.innerHTML = '<svg viewBox="0 0 24 24"><path d="M12 1.6c.8 5.3 5.1 9.6 10.4 10.4-5.3.8-9.6 5.1-10.4 10.4C11.2 17.1 6.9 12.8 1.6 12 6.9 11.2 11.2 6.9 12 1.6Z"/></svg>';
        liveStageLabel = make('span','u453-a','Investigating snapshot…');
        liveStage.append(star, liveStageLabel);
        liveTimeline = make('div','architecture-chat-stream-timeline');
        liveTimeline.hidden = true;
        liveBody = make('div','architecture-chat-markdown');
        liveBox.append(liveStage,liveTimeline,liveBody);
        log.append(qEl,liveBox);
        log.scrollTop = log.scrollHeight;
      };
      try {
        let result;
        if (!evidenceOnly && typeof scope.stream === 'function') {
          try {
            result = await scope.stream(query, signal, {
              onStage(msg) {
                ensureLiveBox();
                if (liveStageLabel) liveStageLabel.textContent = msg || 'Working…';
                else liveStage.textContent = msg || 'Working…';
                status.textContent = msg || 'Working…';
              },
              onToolStart(info) {
                ensureLiveBox();
                liveTimeline.hidden = false;
                const row = make('div','architecture-chat-trace-item');
                row.dataset.step = String(info.step ?? '');
                const pill = make('span','architecture-chat-trace-tool is-running ' + toolTone(info.tool), formatToolName(info.tool));
                pill.title = info.tool || '';
                row.append(pill, make('span','muted','Running…'));
                liveTimeline.append(row);
                log.scrollTop = log.scrollHeight;
              },
              onToolComplete(step) {
                ensureLiveBox();
                liveTimeline.hidden = false;
                const stepKey = String(step.step ?? '');
                const row = Array.from(liveTimeline.children).find(r => r.dataset.step === stepKey) || make('div','architecture-chat-trace-item');
                if (!row.parentNode) liveTimeline.append(row);
                const pill = make('span','architecture-chat-trace-tool ' + toolTone(step.tool), formatToolName(step.tool));
                pill.title = step.tool || '';
                row.replaceChildren(
                  pill,
                  make('span','',step.summary || ''),
                  make('span','architecture-chat-trace-ms',(step.durationMs || 0) + ' ms'));
              },
              onDelta(chunk) {
                if (!chunk) return;
                ensureLiveBox();
                streamedText += chunk;
                if (!rafScheduled) {
                  rafScheduled = true;
                  requestAnimationFrame(() => {
                    rafScheduled = false;
                    if (liveBody) {
                      liveBody.textContent = streamedText;
                      log.scrollTop = log.scrollHeight;
                    }
                  });
                }
              }
            });
          } catch (streamErr) {
            if (signal.aborted || liveBox) throw streamErr;
            result = await scope.send(query,signal);
          }
        } else {
          result = await scope.send(query,signal);
        }
        if (signal.aborted) return;
        const pin = result?.pin;
        if (!pin || pin.graph !== scope.graph || pin.context !== scope.context || pin.snapshot !== scope.snapshot ||
            ['identity','bundle','report'].some(field => pin.finding?.[field] !== scope.finding?.[field]))
          throw new Error('The reply belongs to a different snapshot, context, or finding.');
        const shouldReview = Boolean(!evidenceOnly && result.status !== 'EVIDENCE_ONLY' && result.status !== 'OUT_OF_SCOPE');
        const turn = {question,result,_reviewPending:shouldReview};
        session.turns.push(turn);
        if (!session.title && typeof result.conversationTitle === 'string' && result.conversationTitle.trim())
          session.title = result.conversationTitle.trim().slice(0,100);
        session.updated = Date.now();
        input.value = session.draft = '';
        const stored = save();
        renderConversation();

        // Fire automatic second-pass reviewer non-blocking so the user can immediately type the next message!
        if (shouldReview) {
          const reviewQuery = {...query,reviewer:true};
          if (useReviewerAgent) reviewQuery.agentic = true;
          else delete reviewQuery.agentic;
          const reviewController = new AbortController();
          activeReviewControllers.add(reviewController);
          const reviewSignal = AbortSignal.any([scope.signal, reviewController.signal]);
          const sessionAtDispatch = session;
          const reviewPromise = scope.send(reviewQuery, reviewSignal).then(value => ({value}), error => ({error}));
          reviewPromise.then(review => {
            activeReviewControllers.delete(reviewController);
            turn._reviewPending = false;
            if (reviewSignal.aborted) return;
            const candidate = review.value;
            if (candidate && candidate.pin?.graph === scope.graph && candidate.pin?.context === scope.context && candidate.pin?.snapshot === scope.snapshot
                && ['identity','bundle','report'].every(field => candidate.pin?.finding?.[field] === scope.finding?.[field])) {
              turn.review = candidate;
            } else {
              turn.reviewError = candidate ? 'Reviewer response did not match this snapshot and was withheld.'
                : 'The reviewer could not respond. The primary answer is shown.';
            }
            const slot = reviewSlots.get(turn);
            if (slot && slot.isConnected) {
              if (turn.review) slot.replaceChildren(buildReviewDetails(turn.review));
              else if (turn.reviewError) slot.replaceChildren(make('p','architecture-chat-limitations',turn.reviewError));
            }
            if (session === sessionAtDispatch) save();
          });
        }

        // Unlock controls immediately BEFORE setting status so input.readOnly is false the instant 'Reply received.' appears
        pending = null;
        if (!scope.signal.aborted) {
          submit.disabled = fresh.disabled = mode.disabled = focus.disabled = input.readOnly = false;
          source.disabled = false;
          agentToggleInput.disabled = false;
          reviewerAgenticInput.disabled = false;
          history.disabled = false;
          stop.hidden = true;
        }
        if (stored) status.textContent = result.status === 'OUT_OF_SCOPE' ? 'Try a software architecture question.'
          : evidenceOnly ? 'Captured evidence loaded.' : 'Reply received.';
      } catch (error) {
        request.abort();
        if (!scope.signal.aborted) {
          if (liveBox) renderConversation();
          status.textContent = request.signal.aborted ? 'Stopped waiting. The question is ready to retry.' : error.message;
        }
      } finally {
        pending = null;
        if (!scope.signal.aborted) {
          submit.disabled = fresh.disabled = mode.disabled = focus.disabled = input.readOnly = false;
          source.disabled = false;
          agentToggleInput.disabled = false;
          reviewerAgenticInput.disabled = false;
          history.disabled = false;
          const restore = document.activeElement === stop;
          stop.hidden = true;
          if (restore) input.focus();
        }
      }
    });

    function shortHistory(answer) {
      if (answer.length <= 32000) return answer;
      return answer.slice(0,31980).replace(/[\uD800-\uDBFF]$/,'') + '\n[Reply excerpt]';
    }
    input.addEventListener('keydown',event => {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
        event.preventDefault();
        if (!pending) form.requestSubmit(submit);
      }
    });
    save();
    renderConversation();
    return node;
  };
})();
