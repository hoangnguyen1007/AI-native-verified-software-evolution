/* Lucian UX/UI Prompt #238 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#238 */
/* Lucian UX/UI Prompt #272 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#272 */
/* Lucian UX/UI Prompt #008 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#008 */
/* Lucian UX/UI Prompt #239 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#239 */
/* Lucian UX/UI Prompt #432 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#432 */
/* Lucian UX/UI Prompt #098 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#098 */

(() => {
  'use strict';

  const SVG_NS = 'http://www.w3.org/2000/svg';
  const CLIENT_STORAGE_KEY = 'evo-mcp-client-tab';

  function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = String(text);
    return n;
  }

  function svgEl(tag, attrs = {}) {
    const n = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (v != null) n.setAttribute(k, String(v));
    }
    return n;
  }

  function append(parent, ...children) {
    for (const c of children) {
      if (c != null && c !== false) parent.append(c);
    }
    return parent;
  }

  function graphPath(graphId) {
    return encodeURIComponent(String(graphId ?? '')).replaceAll('%3A', ':');
  }

  function shortSha(sha) {
    const s = String(sha || '').trim();
    if (!s) return '—';
    const raw = s.startsWith('sha256:') ? s.slice(7) : s;
    return 'sha256:' + raw.slice(0, 12) + (raw.length > 12 ? '…' : '');
  }

  function formatBytes(bytes) {
    const b = Number(bytes || 0);
    if (b < 1024) return b + ' B';
    if (b < 1048576) return Math.max(1, Math.round(b / 1024)) + ' KB';
    return (b / 1048576).toFixed(1) + ' MB';
  }

  function arrayBufferToBase64(bufferOrBytes) {
    const bytes = bufferOrBytes instanceof Uint8Array
      ? bufferOrBytes
      : new Uint8Array(bufferOrBytes || 0);
    if (typeof Buffer !== 'undefined' && typeof btoa !== 'function') {
      return Buffer.from(bytes).toString('base64');
    }
    const chunk = 0x8000;
    let bin = '';
    for (let i = 0; i < bytes.length; i += chunk) {
      const slice = bytes.subarray(i, Math.min(i + chunk, bytes.length));
      bin += String.fromCharCode.apply(null, slice);
    }
    return btoa(bin);
  }

  function validateScipUpload(fileOrMeta, expectedShaRaw = '') {
    if (!fileOrMeta || typeof fileOrMeta !== 'object') {
      return { ok: false, error: 'Choose or drop a .scip artifact file first.' };
    }
    const name = String(fileOrMeta.name || '').trim() || 'index.scip';
    const size = Number(fileOrMeta.size ?? 0);
    if (size <= 0) {
      return { ok: false, error: 'Selected SCIP file is empty (0 bytes).' };
    }
    if (size > 16 * 1024 * 1024) {
      return { ok: false, error: 'SCIP capture exceeds the 16 MiB server upload bound.' };
    }
    const rawHash = String(expectedShaRaw || '').trim().toLowerCase();
    const cleanHash = rawHash.startsWith('sha256:') ? rawHash.slice(7) : rawHash;
    if (cleanHash && !/^[0-9a-f]{64}$/.test(cleanHash)) {
      return { ok: false, error: 'Expected SHA-256 must be 64 hexadecimal characters (optional sha256: prefix).' };
    }
    const looksScip = /\.scip(\.bin)?$/i.test(name);
    return {
      ok: true,
      name,
      size,
      expectedSha256: cleanHash || '',
      warning: looksScip ? '' : 'File does not end in .scip; verify it is a valid protobuf SCIP index.'
    };
  }

  function normalizeMcpSetup(raw, origin = '') {
    const src = raw && typeof raw === 'object' ? raw : {};
    const clients = src.clients && typeof src.clients === 'object' ? src.clients : {};
    const port = Number(src.workbenchPort || 0) || 8080;
    const baseOrigin = String(origin || '').replace(/\/$/, '');
    const streamUrl = src.streamableHttpUrl
      || src.streamableHttp?.url
      || clients.antigravity?.url
      || clients.cursor?.url
      || (baseOrigin ? baseOrigin + '/mcp' : `http://127.0.0.1:${port}/mcp`);
    const resources = Array.isArray(src.resources) ? src.resources : [];
    const patRequired = Boolean(src.patRequired ?? src.multiUserMode);
    return {
      evoJarPath: String(src.evoJarPath || src.jarPath || 'cli/target/evo.jar'),
      storeDir: String(src.storeDir || '~/.evo'),
      defaultGraph: String(src.defaultGraph || ''),
      defaultContext: String(src.defaultContext || ''),
      streamableHttpUrl: String(streamUrl),
      protocolVersion: String(src.protocolVersion || '2025-11-25'),
      toolCount: Number(src.toolCount || 21),
      resourceCount: Number(src.resourceCount || resources.length || 6),
      patRequired,
      patMintSupported: Boolean(src.patMintSupported ?? src.multiUserMode ?? patRequired),
      multiUserMode: Boolean(src.multiUserMode ?? patRequired),
      resources,
      agentRuleSnippet: String(src.agentRuleSnippet || ''),
      clients: {
        antigravity: clients.antigravity || null,
        cursor: clients.cursor || null,
        claude: clients.claude || null,
        vscode: clients.vscode || null,
        stdio: clients.stdio || src.stdio || src.workbenchProxyConfig || null,
        offlineStore: clients.offlineStore || src.offlineStore || src.directOfflineConfig || null
      }
    };
  }

  function formatClientConfig(candidate, fallbackObj) {
    if (typeof candidate === 'string' && candidate.trim()) return candidate.trim();
    if (candidate && typeof candidate === 'object') {
      if (typeof candidate.snippet === 'string' && candidate.snippet.trim()) return candidate.snippet.trim();
      if (typeof candidate.json === 'string' && candidate.json.trim()) return candidate.json.trim();
      if (candidate.config && typeof candidate.config === 'object') return JSON.stringify(candidate.config, null, 2);
      if (candidate.mcpServers || candidate.servers || candidate.command || candidate.url) {
        return JSON.stringify(candidate, null, 2);
      }
    }
    return JSON.stringify(fallbackObj, null, 2);
  }

  function buildMcpClientTabs(rawSetup, options = {}) {
    const setup = normalizeMcpSetup(rawSetup, options.origin || '');
    const url = setup.streamableHttpUrl;
    const headers = setup.patRequired ? { Authorization: 'Bearer ${EVO_MCP_TOKEN}' } : undefined;
    const httpEntry = headers ? { url, headers } : { url };
    const antigravityFallback = {
      mcpServers: {
        'evo-workbench': headers
          ? { serverUrl: url, headers }
          : { serverUrl: url }
      }
    };
    const cursorFallback = {
      mcpServers: {
        'evo-workbench': httpEntry
      }
    };
    const claudeFallback = {
      mcpServers: {
        'evo-workbench': {
          type: 'http',
          ...httpEntry
        }
      }
    };
    const vscodeFallback = {
      servers: {
        'evo-workbench': {
          type: 'http',
          ...httpEntry
        }
      }
    };
    const offlineFallback = setup.clients.offlineStore || setup.clients.stdio || {
      mcpServers: {
        'evo-offline': {
          command: 'java',
          args: [
            '-jar', setup.evoJarPath,
            'mcp',
            '--store', setup.storeDir,
            ...(setup.defaultGraph ? ['--graph', setup.defaultGraph] : [])
          ]
        }
      }
    };

    const specs = [
      {
        id: 'antigravity',
        label: 'Antigravity',
        fileHint: '~/.gemini/antigravity/mcp_config.json',
        caption: 'Connects Antigravity directly to this running Workbench over Streamable HTTP.',
        candidate: setup.clients.antigravity,
        fallback: antigravityFallback
      },
      {
        id: 'cursor',
        label: 'Cursor',
        fileHint: '.cursor/mcp.json',
        caption: 'Drop into project or user Cursor MCP settings for live architecture queries.',
        candidate: setup.clients.cursor,
        fallback: cursorFallback
      },
      {
        id: 'claude',
        label: 'Claude Code',
        fileHint: '.mcp.json',
        caption: 'Streamable HTTP MCP server entry for Claude Code CLI & Desktop.',
        candidate: setup.clients.claude,
        fallback: claudeFallback
      },
      {
        id: 'vscode',
        label: 'VS Code',
        fileHint: '.vscode/mcp.json',
        caption: 'Standard VS Code MCP servers manifest using HTTP transport.',
        candidate: setup.clients.vscode,
        fallback: vscodeFallback
      },
      {
        id: 'stdio',
        label: 'CLI Offline',
        fileHint: '100% offline stdio',
        caption: 'Reads captured graphs directly from disk without a running Workbench server.',
        candidate: setup.clients.offlineStore || setup.clients.stdio,
        fallback: offlineFallback
      }
    ];

    return specs.map(spec => ({
      id: spec.id,
      label: spec.label,
      fileHint: String(spec.candidate?.configPath || spec.candidate?.fileHint || spec.fileHint),
      caption: String(spec.candidate?.caption || spec.caption),
      snippet: formatClientConfig(spec.candidate, spec.fallback)
    }));
  }

  function summarizeScipIngest(raw) {
    const d = raw && typeof raw === 'object' ? raw : {};
    const trustApi = typeof window !== 'undefined' ? window.EvoTrust : null;
    const rawTier = String(d.trustTier || 'COMPILER');
    const trustTier = trustApi ? trustApi.normalize(rawTier) : (rawTier === 'COMPILER' ? 'COMPILER' : 'SOURCE_SYNTAX');
    const gaps = Array.isArray(d.coverageGaps) ? d.coverageGaps.map(g => ({
      reasonCode: String(g?.reasonCode || 'SCIP_GAP'),
      symbolOrPath: String(g?.symbolOrPath || ''),
      detail: String(g?.detail || 'No additional detail provided.')
    })) : [];
    const samples = Array.isArray(d.samples) ? d.samples.map(s => ({
      symbol: String(s?.symbol || ''),
      displayName: String(s?.displayName || s?.symbol || 'Symbol'),
      language: String(s?.language || 'polyglot'),
      relativePath: String(s?.relativePath || ''),
      startLine: Number(s?.startLine || 1),
      endLine: Number(s?.endLine || s?.startLine || 1),
      previousTier: String(s?.previousTier || 'SOURCE_SYNTAX'),
      trustTier: String(s?.trustTier || trustTier),
      referenceCount: Number(s?.referenceCount ?? 0)
    })) : [];
    return {
      artifactSha256: String(d.artifactSha256 || ''),
      shortSha: shortSha(d.artifactSha256),
      toolName: String(d.toolName || 'scip-indexer'),
      toolVersion: String(d.toolVersion || ''),
      documentCount: Number(d.documentCount ?? 0),
      symbolCount: Number(d.symbolCount ?? 0),
      upgradedToCompilerCount: Number(d.upgradedToCompilerCount ?? 0),
      occurrenceCount: Number(d.occurrenceCount ?? 0),
      ambiguousDefinitionCount: Number(d.ambiguousDefinitionCount ?? 0),
      coverageGaps: gaps,
      gapCount: gaps.length,
      samples,
      trustTier
    };
  }

  function groupMcpTools(tools) {
    const groups = new Map([
      ['Architecture & Graph', []],
      ['Findings & Policy', []],
      ['Trace & Code Search', []],
      ['Repair & Verification', []],
      ['Other Tools', []]
    ]);
    for (const tool of (Array.isArray(tools) ? tools : [])) {
      const name = String(tool?.name || '');
      const lower = name.toLowerCase();
      let bucket = 'Other Tools';
      if (/(finding|policy|violation|gap|score|health|assessment)/.test(lower)) bucket = 'Findings & Policy';
      else if (/(repair|patch|verify|diagnos|recipe)/.test(lower)) bucket = 'Repair & Verification';
      else if (/(search|trace|slice|source|repo_map|repomap|symbol)/.test(lower)) bucket = 'Trace & Code Search';
      else if (/(graph|context|impact|dsm|communit|bridge|snapshot|module|spring|scip)/.test(lower)) bucket = 'Architecture & Graph';
      groups.get(bucket).push({
        name,
        description: String(tool?.description || 'Read-only Evo MCP tool.'),
        inputSchema: tool?.inputSchema || null
      });
    }
    return [...groups.entries()]
      .filter(([, list]) => list.length > 0)
      .map(([group, items]) => ({ group, items }));
  }

  function buildCopyButtonIcon(kind) {
    const svg = svgEl('svg', { viewBox: '0 0 24 24', 'aria-hidden': 'true', class: kind === 'ok' ? 'u008-ok' : 'u008-cp' });
    if (kind === 'ok') {
      svg.append(svgEl('path', { d: 'M5.5 12.5l4.2 4.2L18.5 8' }));
    } else {
      svg.append(
        svgEl('rect', { x: 8.5, y: 8.5, width: 11, height: 11, rx: 2.5 }),
        svgEl('path', { d: 'M15.5 8.5V6.5a2 2 0 0 0-2-2h-7a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h2' })
      );
    }
    return svg;
  }

  /* Ported Lucian #008 copy pill with morphing check + gleam sweep */
  function createCopyPill(labelPrefix, valueProvider, ariaLabel = 'Copy value') {
    const wrap = el('div', 'ux u008-hub');
    const code = el('code', 'u008-code');
    if (labelPrefix) code.append(el('em', '', labelPrefix));
    const valSpan = el('span', 'u008-val', typeof valueProvider === 'function' ? valueProvider() : valueProvider);
    code.append(valSpan);
    const btn = el('button', 'u008-btn');
    btn.type = 'button';
    btn.setAttribute('aria-label', ariaLabel);
    btn.append(buildCopyButtonIcon('cp'), buildCopyButtonIcon('ok'));
    const tip = el('span', 'u008-tip');
    tip.setAttribute('role', 'status');
    wrap.append(code, btn, tip);

    let timer = 0;
    btn.addEventListener('click', () => {
      const text = typeof valueProvider === 'function' ? valueProvider() : String(valueProvider || '');
      try {
        if (navigator.clipboard && window.isSecureContext) {
          navigator.clipboard.writeText(text).catch(() => {});
        }
      } catch (_) {}
      clearTimeout(timer);
      wrap.classList.remove('is-copied');
      void wrap.offsetWidth;
      wrap.classList.add('is-copied');
      tip.textContent = 'Copied';
      timer = setTimeout(() => {
        if (wrap.isConnected) {
          wrap.classList.remove('is-copied');
          tip.textContent = '';
        }
      }, 1700);
    });
    return { wrap, valSpan, btn };
  }

  /* Pure DOM syntax-highlighted JSON renderer (DOM-only, zero raw HTML strings) */
  function renderJsonTokens(pre, rawText) {
    pre.textContent = '';
    const lines = String(rawText || '').split('\n');
    lines.forEach((line, idx) => {
      const m = line.match(/^(\s*)("([^"]+)")(\s*:\s*)(.*)$/);
      if (m) {
        if (m[1]) pre.append(document.createTextNode(m[1]));
        pre.append(el('span', 'k', m[2]));
        pre.append(el('span', 'p', m[4]));
        const val = m[5];
        if (/^"/.test(val)) pre.append(el('span', 's', val));
        else if (/^\d/.test(val) || /^(true|false|null)/.test(val)) pre.append(el('span', 'n', val));
        else pre.append(document.createTextNode(val));
      } else {
        pre.append(document.createTextNode(line));
      }
      if (idx < lines.length - 1) pre.append(document.createTextNode('\n'));
    });
  }

  /* Ported Lucian #098 rolling-digit KPI card (pure DOM) */
  function createRollingMetricCard(title, initialValue = 0, tone = 'mint') {
    const card = el('div', 'u098-card' + (tone === 'warn' ? ' is-warn' : ''));
    const head = append(el('div', 'u098-h'), el('span', 'u098-dot'), el('span', '', title));
    head.firstChild.setAttribute('aria-hidden', 'true');
    const num = el('div', 'u098-num');
    num.setAttribute('role', 'img');
    card.append(head, num);

    const rm = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches;
    let shown = '';

    function setValue(rawVal) {
      const n = Math.max(0, Math.round(Number(rawVal || 0)));
      const str = n.toLocaleString('en-US');
      const patternChanged = shown.replace(/\d/g, '0') !== str.replace(/\d/g, '0');
      if (patternChanged || !shown) {
        num.textContent = '';
        str.split('').forEach((ch, i) => {
          if (/\d/.test(ch)) {
            const col = el('span', 'u098-col');
            col.style.setProperty('--d', '0');
            col.style.setProperty('--i', String(i));
            for (let d = 0; d <= 9; d++) col.append(el('span', '', String(d)));
            num.append(col);
          } else {
            num.append(el('span', 'u098-sym', ch));
          }
        });
        void num.offsetWidth;
      }
      const cols = num.querySelectorAll('.u098-col');
      let j = 0;
      str.split('').forEach(ch => {
        if (/\d/.test(ch) && cols[j]) {
          cols[j].style.setProperty('--d', rm ? ch : ch);
          j++;
        }
      });
      shown = str;
      num.setAttribute('aria-label', `${title}: ${str}`);
    }

    setValue(initialValue);
    return { card, setValue };
  }

  async function callMcpJsonRpc(endpointUrl, method, params = {}, sessionId = '', _csrfRetried = false) {
    const headers = {
      'Content-Type': 'application/json',
      Accept: 'application/json'
    };
    if (sessionId) headers['Mcp-Session-Id'] = sessionId;
    const csrf = typeof window !== 'undefined' ? window.EvoGitHubAuth?.getCsrfToken?.() : null;
    if (csrf) headers['X-Evo-CSRF'] = csrf;

    const res = await fetch(endpointUrl, {
      method: 'POST',
      credentials: 'same-origin',
      headers,
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: Date.now() + Math.floor(Math.random() * 1000),
        method,
        params
      })
    });
    const nextSession = res.headers.get('Mcp-Session-Id') || res.headers.get('mcp-session-id') || sessionId;
    const text = await res.text();
    let payload = {};
    try { payload = text ? JSON.parse(text) : {}; } catch (_) { payload = {}; }
    if (res.status === 403 && payload.error?.code === 'CSRF_TOKEN_INVALID' && !_csrfRetried
        && typeof window !== 'undefined' && window.EvoGitHubAuth?.refreshAuth) {
      await window.EvoGitHubAuth.refreshAuth({ notifyOnChange: false }).catch(() => {});
      return callMcpJsonRpc(endpointUrl, method, params, sessionId, true);
    }
    if (!res.ok || payload.error) {
      const msg = payload.error?.message || `MCP HTTP ${res.status}`;
      throw Object.assign(new Error(msg), { status: res.status, code: payload.error?.code });
    }
    return { result: payload.result || {}, sessionId: nextSession || payload.result?.sessionId || '' };
  }

  async function closeMcpSession(endpointUrl, sessionId) {
    if (!sessionId) return;
    const headers = { 'Mcp-Session-Id': sessionId };
    const csrf = typeof window !== 'undefined' ? window.EvoGitHubAuth?.getCsrfToken?.() : null;
    if (csrf) headers['X-Evo-CSRF'] = csrf;
    try {
      await fetch(endpointUrl, { method: 'DELETE', credentials: 'same-origin', headers });
    } catch (_) {}
  }

  async function openMcpStudio(api) {
    const dialog = document.getElementById('mcp-setup-dialog');
    const body = document.getElementById('mcp-setup-body');
    if (!dialog || !body) return;
    body.replaceChildren(el('p', 'muted', 'Loading live MCP Studio…'));
    if (!dialog.open) dialog.showModal();

    try {
      const raw = await api('/mcp/setup', undefined, undefined, 'GET');
      const setup = normalizeMcpSetup(raw, window.location.origin);
      const clientTabs = buildMcpClientTabs(setup, { origin: window.location.origin });
      const rm = window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches;

      const shell = el('div', 'evo-hub-shell ux');

      // ── Step 1: Lucian #238 Live Status Header + #008 Endpoint Copy Pill ──
      const statusCard = el('section', 'u238-hub');
      const pulse = el('span', 'u238-pu');
      pulse.setAttribute('aria-hidden', 'true');
      const hostName = (() => {
        try { return new URL(setup.streamableHttpUrl).host; } catch (_) { return '127.0.0.1'; }
      })();
      const statusCopy = append(el('div', 'u238-hub-copy'),
        el('b', '', `Streamable HTTP MCP · Live on ${hostName}`),
        el('small', '', `Protocol ${setup.protocolVersion} · ${setup.toolCount} read-only tools · ${setup.resourceCount} evo:// resources`)
      );
      const endpointCopy = createCopyPill('HTTP', setup.streamableHttpUrl, 'Copy Streamable HTTP MCP URL');

      const barsRow = el('div', 'u238-bars-row');
      const channels = [
        ['Streamable HTTP', 'Ready'],
        ['Read-Only Tools', `${setup.toolCount} tools`],
        ['Auth Policy', setup.patRequired ? 'PAT Bearer' : 'Loopback']
      ];
      channels.forEach(([label, stateTxt], ci) => {
        const item = el('div', 'u238-it');
        item.append(append(el('div', 'u238-tp'), el('span', '', label), el('em', '', stateTxt)));
        const bar = el('div', 'u238-bar');
        for (let b = 0; b < 18; b++) {
          const tick = el('i');
          if (label === 'Auth Policy' && setup.patRequired && b === 17) tick.className = 'w';
          tick.style.transitionDelay = (b * 0.016 + ci * 0.05) + 's';
          bar.append(tick);
        }
        item.append(bar);
        barsRow.append(item);
      });
      statusCard.append(
        append(el('div', 'u238-hub-top'), append(el('div', 'u238-hub-status'), pulse, statusCopy), endpointCopy.wrap),
        barsRow
      );
      requestAnimationFrame(() => statusCard.classList.add('is-in'));

      // ── Step 2: Lucian #272 Sliding-Pill Client Config Selector ──
      const step2 = el('section', 'evo-hub-step');
      step2.append(
        append(el('div', 'evo-hub-step-head'),
          append(el('h3', 'evo-hub-step-title'), el('span', 'evo-hub-step-num', '1'), el('span', '', 'Choose your IDE or CLI client')),
          el('span', 'small muted', setup.defaultGraph ? `Default graph: ${shortSha(setup.defaultGraph)}` : 'Auto-binds latest snapshot')),
        el('p', 'evo-hub-caption', 'Select a client below and paste the generated snippet. No custom wrapper scripts needed.')
      );

      const box = el('div', 'u272-hub');
      const tablist = el('div', 'u272-tl');
      tablist.setAttribute('role', 'tablist');
      tablist.setAttribute('aria-label', 'MCP Client');
      const pill = el('span', 'u272-pl');
      pill.setAttribute('aria-hidden', 'true');
      tablist.append(pill);

      const bar = el('div', 'u272-bar');
      const hintWrap = el('div', 'u272-hint');
      const captionSpan = el('span');
      const pathCode = el('code', 'u272-path');
      hintWrap.append(captionSpan, pathCode);

      let activeTabIdx = 0;
      const snippetCopy = createCopyPill('', () => clientTabs[activeTabIdx]?.snippet || '', 'Copy client MCP configuration');
      bar.append(hintWrap, snippetCopy.wrap);

      const pre = el('pre', 'u272-pre');
      pre.setAttribute('role', 'tabpanel');
      box.append(tablist, bar, pre);

      let curIdx = -1;
      const tabButtons = clientTabs.map((tab, idx) => {
        const b = el('button', 'u272-tb', tab.label);
        b.type = 'button';
        b.setAttribute('role', 'tab');
        b.addEventListener('click', () => pickTab(idx));
        b.addEventListener('keydown', e => {
          const d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
          if (!d) return;
          e.preventDefault();
          const next = (idx + d + clientTabs.length) % clientTabs.length;
          pickTab(next);
          tabButtons[next].focus();
        });
        tablist.append(b);
        return b;
      });

      function movePill(btn) {
        if (!btn) return;
        pill.style.width = btn.offsetWidth + 'px';
        pill.style.transform = `translateX(${btn.offsetLeft}px)`;
      }

      function pickTab(idx) {
        if (idx === curIdx) return;
        const first = curIdx < 0;
        curIdx = idx;
        activeTabIdx = idx;
        tabButtons.forEach((b, k) => {
          b.setAttribute('aria-selected', String(k === idx));
          b.tabIndex = k === idx ? 0 : -1;
        });
        movePill(tabButtons[idx]);
        const tab = clientTabs[idx];
        captionSpan.textContent = tab.caption;
        pathCode.textContent = tab.fileHint;
        try { localStorage.setItem(CLIENT_STORAGE_KEY, tab.id); } catch (_) {}
        if (first || rm) {
          renderJsonTokens(pre, tab.snippet);
          return;
        }
        pre.classList.remove('is-in');
        pre.classList.add('is-out');
        setTimeout(() => {
          renderJsonTokens(pre, tab.snippet);
          pre.classList.remove('is-out');
          void pre.offsetWidth;
          pre.classList.add('is-in');
        }, 160);
      }

      const savedId = (() => { try { return localStorage.getItem(CLIENT_STORAGE_KEY); } catch (_) { return ''; } })();
      const initialIdx = Math.max(0, clientTabs.findIndex(t => t.id === savedId));
      pickTab(initialIdx);
      requestAnimationFrame(() => movePill(tabButtons[curIdx]));

      step2.append(box);

      // PAT Token Reminder & Quick Open to Workspace Settings -> API & MCP Tokens
      const isSignedIn = Boolean(window.EvoGitHubAuth?.isAuthenticated?.());
      if (setup.patRequired || setup.patMintSupported || isSignedIn) {
        const patBanner = el('div', 'evo-hub-pat-banner');
        const patCopy = append(el('div'),
          el('strong', '', setup.patRequired ? 'Scoped PAT Required for Remote IDE Access' : 'Optional Read-Only MCP Token'),
          el('div', 'small muted', 'Create or revoke read-only mcp:read Personal Access Tokens in Workspace Settings → API & MCP Tokens.')
        );
        const patActions = el('div', 'evo-export-group');
        const openTokensBtn = el('button', 'button subtle', 'Open API & MCP Tokens →');
        openTokensBtn.type = 'button';
        openTokensBtn.addEventListener('click', () => {
          dialog.close();
          window.EvoGitHubAuth?.openWorkspaceAccountModal?.('tokens');
        });
        patActions.append(openTokensBtn);
        const patRevealHost = el('div', 'evo-pat-minted-host');
        if (isSignedIn) {
          const mintBtn = el('button', 'button primary', 'Mint mcp:read Token');
          mintBtn.type = 'button';
          mintBtn.addEventListener('click', async () => {
            mintBtn.disabled = true;
            try {
              const minted = await api('/auth/tokens', { label: 'IDE MCP Studio', scopes: ['mcp:read'], expiresInDays: 90 }, undefined, 'POST');
              const tok = String(minted?.token || '');
              if (tok) {
                const reveal = createCopyPill('PAT', tok, 'Copy newly minted MCP token');
                patRevealHost.replaceChildren(reveal.wrap);
              }
            } catch (err) {
              patCopy.lastChild.textContent = err.message || 'Sign in first to mint a Personal Access Token.';
            } finally {
              mintBtn.disabled = false;
            }
          });
          patActions.append(mintBtn);
          dialog.addEventListener('close', () => { patRevealHost.replaceChildren(); }, { once: true });
        }
        patBanner.append(patCopy, patActions, patRevealHost);
        step2.append(patBanner);
      }

      // ── Step 3: Lucian #239 Interactive Live Connection Tester ──
      const step3 = el('section', 'evo-hub-step');
      step3.append(
        append(el('div', 'evo-hub-step-head'),
          append(el('h3', 'evo-hub-step-title'), el('span', 'evo-hub-step-num', '2'), el('span', '', 'Test live connection & browse tools'))),
        el('p', 'evo-hub-caption', 'Runs a real JSON-RPC 2.0 handshake (initialize → tools/list → resources/list) against this Workbench.')
      );

      const tester = el('div', 'u239-hub');
      const reqCard = el('div', 'u239-req-card');
      const reqHead = el('div', 'u239-tl');
      reqHead.append(el('span', 'u239-method', 'POST'), el('span', 'u239-ep', setup.streamableHttpUrl));

      const goBtn = el('button', 'u239-go');
      goBtn.type = 'button';
      const playSvg = svgEl('svg', { viewBox: '0 0 11 11', 'aria-hidden': 'true' });
      playSvg.append(svgEl('path', { d: 'M2 1.5l7.5 4L2 9.5l1.4-4z' }));
      goBtn.append(playSvg, el('span', '', 'Test Live Connection'));
      reqHead.append(goBtn);

      const reqPre = el('pre', 'u239-pre',
        '1. initialize      {"protocolVersion":"' + setup.protocolVersion + '"}\n'
        + '2. tools/list      {}\n'
        + '3. resources/list  {}');
      reqCard.append(reqHead, reqPre);

      const wire = append(el('div', 'u239-wire'), el('span', 'u239-ln'), el('span', 'u239-dot'));
      wire.setAttribute('aria-hidden', 'true');
      const dot = wire.lastChild;

      const resCard = el('div', 'u239-rs');
      resCard.setAttribute('aria-live', 'polite');
      const resStatusBadge = el('b', '', '200 OK');
      const resMs = el('span', 'u239-ms', '');
      const resHead = append(el('div', 'u239-rh'),
        el('span', '', 'Live Handshake Result'),
        append(el('span', 'u239-st'), resStatusBadge, resMs)
      );
      const resPh = el('div', 'u239-ph', 'Click "Test Live Connection" to verify the Streamable HTTP endpoint and inspect all tools.');
      const resBody = el('div', 'u239-body');
      resBody.hidden = true;
      resCard.append(resHead, resPh, resBody);

      tester.append(reqCard, wire, resCard);

      const renderCatalogResult = (tools, resources, serverInfo) => {
        resBody.textContent = '';
        if (serverInfo?.name) {
          resBody.append(el('p', 'small muted', `Server: ${serverInfo.name} ${serverInfo.version || ''} · Protocol ${setup.protocolVersion}`));
        }
        const grouped = groupMcpTools(tools);
        const detailBox = el('div', 'evo-mcp-chip-detail');
        detailBox.hidden = true;

        for (const grp of grouped) {
          const gWrap = el('div', 'evo-mcp-catalog-group');
          gWrap.append(append(el('div', 'evo-mcp-catalog-heading'),
            el('span', '', grp.group),
            el('span', '', `${grp.items.length}`)));
          const chips = el('div', 'evo-mcp-chips');
          for (const item of grp.items) {
            const chip = el('button', 'evo-mcp-chip', item.name);
            chip.type = 'button';
            chip.setAttribute('aria-pressed', 'false');
            chip.addEventListener('click', () => {
              resBody.querySelectorAll('.evo-mcp-chip').forEach(c => c.setAttribute('aria-pressed', 'false'));
              chip.setAttribute('aria-pressed', 'true');
              const props = Object.keys(item.inputSchema?.properties || {});
              detailBox.hidden = false;
              detailBox.replaceChildren(
                el('strong', '', item.name),
                el('div', '', item.description),
                props.length ? el('div', 'small muted', `Parameters: ${props.join(', ')}`) : null
              );
            });
            chips.append(chip);
          }
          gWrap.append(chips);
          resBody.append(gWrap);
        }
        resBody.append(detailBox);

        const resList = resources.length ? resources : setup.resources;
        if (resList.length) {
          const rWrap = el('div', 'evo-mcp-catalog-group');
          rWrap.append(append(el('div', 'evo-mcp-catalog-heading'),
            el('span', '', 'evo:// Resources'),
            el('span', '', `${resList.length}`)));
          const grid = el('div', 'evo-mcp-resources-grid');
          for (const r of resList) {
            grid.append(append(el('div', 'evo-mcp-resource-card'),
              el('code', '', r.uri || ''),
              el('span', '', r.description || r.name || 'MCP resource')));
          }
          rWrap.append(grid);
          resBody.append(rWrap);
        }
      };

      goBtn.addEventListener('click', async () => {
        goBtn.disabled = true;
        tester.classList.remove('is-res', 'is-err');
        resPh.hidden = false;
        resPh.textContent = 'Running initialize → tools/list → resources/list…';
        resBody.hidden = true;
        if (!rm && dot.animate) {
          dot.animate(
            [
              { transform: 'translateY(-4px)', opacity: 0 },
              { opacity: 1, offset: 0.25 },
              { transform: 'translateY(14px)', opacity: 1, offset: 0.85 },
              { transform: 'translateY(18px) scale(1.4)', opacity: 0 }
            ],
            { duration: 550, easing: 'cubic-bezier(.5,0,.3,1)' }
          );
        }
        const t0 = performance.now();
        const mcpEndpoint = (() => {
          try { return new URL(setup.streamableHttpUrl).pathname || '/mcp'; } catch (_) { return '/mcp'; }
        })();
        let sid = '';
        try {
          const init = await callMcpJsonRpc(mcpEndpoint, 'initialize', {
            protocolVersion: setup.protocolVersion,
            capabilities: {},
            clientInfo: { name: 'evo-workbench-studio', version: '1.0.0' }
          });
          sid = init.sessionId;
          const [toolsRes, resourcesRes] = await Promise.all([
            callMcpJsonRpc(mcpEndpoint, 'tools/list', {}, sid),
            callMcpJsonRpc(mcpEndpoint, 'resources/list', {}, sid)
          ]);
          const elapsed = Math.max(1, Math.round(performance.now() - t0));
          const tools = Array.isArray(toolsRes.result?.tools) ? toolsRes.result.tools : [];
          const resources = Array.isArray(resourcesRes.result?.resources) ? resourcesRes.result.resources : setup.resources;
          resStatusBadge.textContent = '200 OK';
          resMs.textContent = `${elapsed} ms · ${tools.length} tools · ${resources.length} resources` + (sid ? ` · session ${sid.slice(0, 8)}…` : '');
          tester.classList.add('is-res');
          resPh.hidden = true;
          resBody.hidden = false;
          renderCatalogResult(tools, resources, init.result?.serverInfo);
        } catch (err) {
          const elapsed = Math.max(1, Math.round(performance.now() - t0));
          tester.classList.add('is-res', 'is-err');
          resStatusBadge.textContent = err.status ? `HTTP ${err.status}` : 'ERROR';
          resMs.textContent = `${elapsed} ms`;
          resPh.hidden = true;
          resBody.hidden = false;
          resBody.replaceChildren(el('p', 'small', err.message || 'MCP connection test failed. Check server status or PAT authentication.'));
        } finally {
          if (sid) void closeMcpSession(mcpEndpoint, sid);
          goBtn.disabled = false;
        }
      });

      step3.append(tester);
      shell.append(statusCard, step2, step3);
      body.replaceChildren(shell);
    } catch (err) {
      body.replaceChildren(el('p', 'muted', 'Could not load MCP Studio: ' + err.message));
    }
  }

  async function readFileBytes(file) {
    if (typeof file.arrayBuffer === 'function') {
      return new Uint8Array(await file.arrayBuffer());
    }
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(new Uint8Array(reader.result));
      reader.onerror = () => reject(reader.error || new Error('Failed to read file.'));
      reader.readAsArrayBuffer(file);
    });
  }

  async function computeSha256Hex(bytes) {
    try {
      if (window.crypto?.subtle) {
        const digest = await window.crypto.subtle.digest('SHA-256', bytes);
        return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, '0')).join('');
      }
    } catch (_) {}
    return '';
  }

  async function openScipDrawer(api, context = {}) {
    const dialog = document.getElementById('scip-upgrade-dialog');
    const body = document.getElementById('scip-upgrade-body');
    if (!dialog || !body) return;
    if (!dialog.open) dialog.showModal();

    const stateGraph = context.graphId || window.EvoApp?.state?.graph || window.EvoBus?.getState?.()?.graphId || '';
    const trustApi = window.EvoTrust;

    const shell = el('div', 'evo-hub-shell ux');

    // Honest explainer banner
    const explainer = el('div', 'evo-scip-explainer');
    explainer.append(
      append(el('div'),
        el('strong', '', 'Host-Safe Polyglot Semantic Upgrade: '),
        document.createTextNode('Ingests verified SCIP captures from CI or isolated workers without executing language indexers on host.')),
      trustApi ? trustApi.renderBadge('COMPILER', { compact: true }) : el('span', 'badge good', 'COMPILER')
    );
    shell.append(explainer);

    if (!stateGraph) {
      shell.append(el('p', 'muted', 'Select or analyze a repository first so there is an active graph snapshot to upgrade with SCIP evidence.'));
      body.replaceChildren(shell);
      return;
    }

    // ── Step 1: Ported Lucian #432 Dropzone + Optional SHA-256 Pin ──
    const step1 = el('section', 'evo-hub-step');
    step1.append(
      append(el('div', 'evo-hub-step-head'),
        append(el('h3', 'evo-hub-step-title'), el('span', 'evo-hub-step-num', '1'), el('span', '', 'Upload verified .scip capture')),
        el('code', 'small muted', `Target graph: ${shortSha(stateGraph)}`))
    );

    const ingestGrid = el('div', 'evo-scip-ingest-grid');

    // Ported #432 markup using pure DOM + SVG
    const dzRoot = el('div', 'u432-hub');
    const dz = el('div', 'u432-dz');
    dz.tabIndex = 0;
    dz.setAttribute('role', 'button');
    dz.setAttribute('aria-label', 'Upload a .scip index file');

    const idle = el('div', 'u432-idle');
    const arrowBox = el('span', 'u432-ar');
    arrowBox.setAttribute('aria-hidden', 'true');
    const upSvg = svgEl('svg', { viewBox: '0 0 24 24' });
    upSvg.append(
      svgEl('path', { d: 'M12 16V5M7 9.5 12 4.5l5 5' }),
      svgEl('path', { d: 'M5 15v2.5A2.5 2.5 0 0 0 7.5 20h9a2.5 2.5 0 0 0 2.5-2.5V15' })
    );
    arrowBox.append(upSvg);
    const b1 = el('b', '', 'Drop index.scip here');
    const b2 = el('b', '', 'Let go to ingest');
    b2.setAttribute('aria-hidden', 'true');
    const browseBtn = el('button', 'u432-br', 'browse');
    browseBtn.type = 'button';
    browseBtn.tabIndex = -1;
    const hintSmall = el('small');
    hintSmall.append(document.createTextNode('or '), browseBtn, document.createTextNode(' · .scip up to 16 MiB'));
    idle.append(arrowBox, b1, b2, hintSmall);

    const up = el('div', 'u432-up');
    up.setAttribute('aria-hidden', 'true');
    const stage = el('div', 'u432-st');
    const ringSvg = svgEl('svg', { class: 'u432-ring', viewBox: '0 0 100 100' });
    const ringTk = svgEl('circle', { class: 'tk', cx: 50, cy: 50, r: 46 });
    const ringPg = svgEl('circle', { class: 'pg', cx: 50, cy: 50, r: 46 });
    ringSvg.append(ringTk, ringPg);

    const docSvg = svgEl('svg', { class: 'u432-doc', viewBox: '0 0 50 62' });
    docSvg.append(
      svgEl('path', { class: 'pp', d: 'M6 1h26l15 15v39a6 6 0 0 1-6 6H6a6 6 0 0 1-6-6V7a6 6 0 0 1 6-6z' }),
      svgEl('path', { class: 'fd', d: 'M32 1v10a5 5 0 0 0 5 5h10z' }),
      svgEl('rect', { class: 'ln', x: 9, y: 22, width: 24, height: 3, rx: 1.5 }),
      svgEl('rect', { class: 'ln', x: 9, y: 29, width: 30, height: 3, rx: 1.5 }),
      svgEl('rect', { class: 'tg', x: -4, y: 40, width: 32, height: 14, rx: 3.5 })
    );
    const docTagText = svgEl('text', { x: 12, y: 50, 'text-anchor': 'middle' });
    docTagText.textContent = 'SCIP';
    docSvg.append(docTagText);

    const okBadge = el('span', 'u432-ok');
    const okSvg = svgEl('svg', { viewBox: '0 0 24 24' });
    okSvg.append(svgEl('path', { d: 'm5 12.5 4.5 4.5L19 7.5' }));
    okBadge.append(okSvg);

    stage.append(ringSvg, docSvg, okBadge);
    const fileNm = el('p', 'u432-nm');
    up.append(stage, fileNm);

    const fileInput = el('input', 'u432-file');
    fileInput.type = 'file';
    fileInput.accept = '.scip,.bin,application/octet-stream';
    fileInput.tabIndex = -1;
    fileInput.setAttribute('aria-hidden', 'true');

    const liveRegion = el('p', 'u432-live');
    liveRegion.setAttribute('role', 'status');

    dz.append(idle, up, fileInput);
    dzRoot.append(dz, liveRegion);

    // Form options column
    const formCol = el('div', 'evo-scip-form-col');
    const shaField = el('div', 'evo-scip-field');
    const shaInput = el('input');
    shaInput.id = 'scip-expected-sha';
    shaInput.placeholder = 'Optional 64-hex SHA-256 or sha256:…';
    shaInput.autocomplete = 'off';
    shaInput.spellcheck = false;
    const shaLabel = el('label', '', 'Expected SHA-256 (optional integrity pin)');
    shaLabel.htmlFor = shaInput.id;
    shaField.append(shaLabel, shaInput);

    const srcField = el('div', 'evo-scip-field');
    const srcInput = el('input');
    srcInput.id = 'scip-source-label';
    srcInput.placeholder = 'e.g. ci-scip-typescript-main';
    srcInput.autocomplete = 'off';
    const srcLabel = el('label', '', 'Capture source label (optional)');
    srcLabel.htmlFor = srcInput.id;
    srcField.append(srcLabel, srcInput);

    const localMeta = el('p', 'small muted', 'No .scip file staged yet.');
    const errorMsg = el('p', 'form-error');
    errorMsg.setAttribute('role', 'alert');
    errorMsg.hidden = true;

    const ingestBtn = el('button', 'button primary', 'Ingest & Upgrade Graph');
    ingestBtn.type = 'button';
    ingestBtn.disabled = true;

    formCol.append(shaField, srcField, localMeta, errorMsg, ingestBtn);
    ingestGrid.append(dzRoot, formCol);
    step1.append(ingestGrid);

    // ── Step 2: Ported Lucian #098 Rolling-Digit KPIs + Samples + Gaps ──
    const resultsSection = el('section', 'evo-hub-step');
    resultsSection.hidden = true;

    const mDocs = createRollingMetricCard('Documents', 0, 'mint');
    const mSyms = createRollingMetricCard('Symbols', 0, 'mint');
    const mUpgraded = createRollingMetricCard('Upgraded → COMPILER', 0, 'mint');
    const mGaps = createRollingMetricCard('Coverage Gaps', 0, 'warn');
    const kpiGrid = append(el('div', 'u098-kpi-grid'), mDocs.card, mSyms.card, mUpgraded.card, mGaps.card);

    const lineageBar = el('div', 'evo-scip-lineage');
    const samplesHost = el('div');
    const gapsHost = el('div', 'evo-scip-gaps');

    resultsSection.append(
      append(el('div', 'evo-hub-step-head'),
        append(el('h3', 'evo-hub-step-title'), el('span', 'evo-hub-step-num', '2'), el('span', '', 'Verified SCIP Upgrade Results'))),
      kpiGrid,
      lineageBar,
      samplesHost,
      gapsHost
    );

    let staged = null;
    let busy = false;

    async function stageFile(file) {
      if (busy || !file) return;
      errorMsg.hidden = true;
      const check = validateScipUpload(file, shaInput.value);
      if (!check.ok) {
        errorMsg.textContent = check.error;
        errorMsg.hidden = false;
        return;
      }
      dzRoot.classList.remove('is-done', 'is-err', 'is-drag');
      dzRoot.classList.add('is-up');
      ringPg.style.strokeDashoffset = '200';
      fileNm.replaceChildren(document.createTextNode(file.name + ' '), el('small', '', '· reading…'));

      try {
        const bytes = await readFileBytes(file);
        const scipBase64 = arrayBufferToBase64(bytes);
        const computedSha = await computeSha256Hex(bytes);
        ringPg.style.strokeDashoffset = '90';
        staged = { name: file.name, size: file.size, scipBase64, computedSha };
        if (!srcInput.value.trim()) srcInput.value = file.name;
        localMeta.textContent = `${file.name} (${formatBytes(file.size)})` + (computedSha ? ` · local sha256:${computedSha.slice(0, 12)}…` : '') + (check.warning ? ` · ${check.warning}` : '');
        ingestBtn.disabled = false;
        await runIngest();
      } catch (err) {
        dzRoot.classList.remove('is-up');
        errorMsg.textContent = err.message || 'Could not read SCIP file.';
        errorMsg.hidden = false;
      }
    }

    async function runIngest() {
      if (busy || !staged) return;
      const check = validateScipUpload({ name: staged.name, size: staged.size }, shaInput.value);
      if (!check.ok) {
        errorMsg.textContent = check.error;
        errorMsg.hidden = false;
        return;
      }
      busy = true;
      ingestBtn.disabled = true;
      errorMsg.hidden = true;
      dzRoot.classList.remove('is-done', 'is-err');
      dzRoot.classList.add('is-up');
      ringPg.style.strokeDashoffset = '55';
      fileNm.replaceChildren(document.createTextNode(staged.name + ' '), el('small', '', '· verifying…'));

      try {
        const reqBody = {
          scipBase64: staged.scipBase64,
          ...(check.expectedSha256 ? { expectedSha256: check.expectedSha256 } : {}),
          ...(srcInput.value.trim() ? { sourceLabel: srcInput.value.trim() } : {})
        };
        const data = await api(`/graphs/${graphPath(stateGraph)}/scip/ingest`, reqBody, undefined, 'POST');
        const summary = summarizeScipIngest(data);

        ringPg.style.strokeDashoffset = '0';
        dzRoot.classList.add('is-done');
        fileNm.replaceChildren(document.createTextNode(staged.name + ' '), el('small', '', `· ${formatBytes(staged.size)}`));
        liveRegion.textContent = `SCIP ingest complete: ${summary.upgradedToCompilerCount} symbols upgraded to COMPILER.`;

        resultsSection.hidden = false;
        mDocs.setValue(summary.documentCount);
        mSyms.setValue(summary.symbolCount);
        mUpgraded.setValue(summary.upgradedToCompilerCount);
        mGaps.setValue(summary.gapCount);

        const shaCopy = createCopyPill('SHA-256', summary.artifactSha256 || staged.computedSha || '—', 'Copy SCIP artifact SHA-256');
        lineageBar.replaceChildren(
          trustApi ? trustApi.renderBadge(summary.trustTier) : el('span', 'badge good', summary.trustTier),
          el('span', 'badge', `${summary.toolName}${summary.toolVersion ? ' ' + summary.toolVersion : ''}`),
          el('span', 'small muted', `${summary.occurrenceCount} occurrences · ${summary.ambiguousDefinitionCount} ambiguous`),
          shaCopy.wrap
        );

        samplesHost.textContent = '';
        if (summary.samples.length) {
          const tableWrap = el('div', 'evo-scip-samples-wrap');
          const table = el('table', 'evo-scip-table');
          const thead = el('thead');
          thead.append(append(el('tr'),
            el('th', '', 'Symbol'),
            el('th', '', 'Language'),
            el('th', '', 'Trust Upgrade'),
            el('th', '', 'Refs'),
            el('th', '', 'Captured Source')
          ));
          const tbody = el('tbody');
          for (const s of summary.samples) {
            const pathCell = el('td');
            if (s.relativePath) {
              const pBtn = el('button', 'evo-scip-path-btn', `${s.relativePath}:${s.startLine}`);
              pBtn.type = 'button';
              pBtn.addEventListener('click', () => {
                dialog.close();
                window.EvoBus?.emit?.('evo:source-open', {
                  path: s.relativePath,
                  startLine: s.startLine || 1,
                  endLine: s.endLine || null,
                  highlightLine: s.startLine || 1
                });
              });
              pathCell.append(pBtn);
            } else {
              pathCell.textContent = '—';
            }
            const trustCell = el('td');
            if (trustApi) {
              trustCell.append(
                trustApi.renderBadge(s.previousTier, { compact: true }),
                document.createTextNode(' → '),
                trustApi.renderBadge(s.trustTier, { compact: true })
              );
            } else {
              trustCell.textContent = `${s.previousTier} → ${s.trustTier}`;
            }
            tbody.append(append(el('tr'),
              append(el('td'), el('strong', '', s.displayName)),
              el('td', '', s.language),
              trustCell,
              el('td', '', String(s.referenceCount)),
              pathCell
            ));
          }
          table.append(thead, tbody);
          tableWrap.append(table);
          samplesHost.append(tableWrap);
        } else {
          samplesHost.append(el('p', 'small muted', 'No symbol sample rows returned for this SCIP capture.'));
        }

        gapsHost.textContent = '';
        if (summary.coverageGaps.length) {
          gapsHost.append(el('h4', 'small', `Remaining Coverage Gaps (${summary.coverageGaps.length})`));
          for (const g of summary.coverageGaps) {
            gapsHost.append(append(el('div', 'evo-scip-gap-card'),
              append(el('div', 'evo-hub-step-head'),
                el('code', '', g.reasonCode),
                g.symbolOrPath ? el('span', 'small muted', g.symbolOrPath) : null),
              el('span', '', g.detail)
            ));
          }
        } else {
          gapsHost.append(el('p', 'small muted', '0 remaining coverage gaps reported in this SCIP capture.'));
        }
      } catch (err) {
        dzRoot.classList.add('is-err');
        errorMsg.textContent = err.message || 'SCIP ingest failed.';
        errorMsg.hidden = false;
      } finally {
        busy = false;
        ingestBtn.disabled = !staged;
      }
    }

    dz.addEventListener('click', e => { if (!busy && e.target !== fileInput) fileInput.click(); });
    dz.addEventListener('keydown', e => { if ((e.key === 'Enter' || e.key === ' ') && !busy) { e.preventDefault(); fileInput.click(); } });
    fileInput.addEventListener('change', () => { if (fileInput.files?.[0]) void stageFile(fileInput.files[0]); fileInput.value = ''; });

    let dragDepth = 0;
    dz.addEventListener('dragenter', e => { e.preventDefault(); dragDepth++; if (!busy) dzRoot.classList.add('is-drag'); });
    dz.addEventListener('dragover', e => { e.preventDefault(); if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'; });
    dz.addEventListener('dragleave', () => { dragDepth = Math.max(0, dragDepth - 1); if (!dragDepth) dzRoot.classList.remove('is-drag'); });
    dz.addEventListener('drop', e => {
      e.preventDefault();
      dragDepth = 0;
      dzRoot.classList.remove('is-drag');
      if (e.dataTransfer?.files?.length) void stageFile(e.dataTransfer.files[0]);
    });
    ingestBtn.addEventListener('click', () => void runIngest());

    shell.append(step1, resultsSection);
    body.replaceChildren(shell);
  }

  const apiObj = Object.freeze({
    normalizeMcpSetup,
    buildMcpClientTabs,
    arrayBufferToBase64,
    validateScipUpload,
    summarizeScipIngest,
    groupMcpTools,
    openMcpStudio,
    openScipDrawer
  });

  if (typeof window !== 'undefined') window.EvoMcpScipHub = apiObj;
  if (typeof module !== 'undefined' && module.exports) module.exports = apiObj;
})();
