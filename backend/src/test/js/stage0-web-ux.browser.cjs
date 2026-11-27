'use strict';

/**
 * Stage-0 Web App E2E Verification Suite (Headless Edge):
 * 1. Strict zero-`https:` CSP enforcement (zero console/page/CSP errors).
 * 2. Rendered XSS-safe Markdown Wiki Dossier modal (headings, GFM tables, fenced code blocks,
 *    lists, neutralized <img onerror>, and clickable relative inter-page Markdown links switching
 *    pages inside the modal!) + 320px & 1440px zero-overflow audit.
 * 3. Multi-User Scoped PAT lifecycle in Account Settings ("API & MCP Tokens" tab: mint, one-time
 *    reveal, CSRF check, list without plaintext, revoke) + 1-Click MCP PAT helper inside
 *    "Connect IDE (MCP)" modal (real resolved paths, no "evo.jar" / "<path-to-evo-store>",
 *    ephemeral secret cleared on close) + 320px & 1440px zero-overflow audit.
 * 4. Repair Studio blank focusFiles RepoMap placeholder + Leiden/Advisory context badges rendering.
 * 5. AI Chat new toolMeta labels (architecture_brief, inspect_advisory_links, read_markdown_section,
 *    inspect_codeowners) rendered in agent trace.
 */

const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { readFile } = require('node:fs/promises');
const path = require('node:path');
const { edge, until } = require('./m9-edge.cjs');

async function main([browser, output]) {
  assert.ok(browser && output, 'Usage: node stage0-web-ux.browser.cjs <edge-exe> <output-dir>');
  const webDir = path.resolve(__dirname, '../../main/resources/web');
  const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' };
  const strictCsp = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";

  const graphId = 'sha256:' + '1'.repeat(64);
  const snapId = 'snapshot:sha256:' + '2'.repeat(64);
  const ctxId = 'analysis:sha256:' + '3'.repeat(64);
  const propId = 'sha256:' + '9'.repeat(64);

  const csrfToken = 'csrf-stage0-' + 'a'.repeat(32);
  let authenticated = true;
  const tokens = [];
  let tokenSeq = 1;
  const csrfChecked = [];
  let lastMcpMintScopes = null;

  const user = {
    userId: 'user-alice',
    login: 'alice',
    displayName: 'Alice Architect',
    name: 'Alice Architect',
    email: 'alice@acme.dev',
    role: 'ADMIN',
    avatarUrl: '',
    authMethod: 'LOCAL_PASSWORD',
    githubLinked: false,
    githubLogin: '',
    disabled: false,
    createdAtMs: Date.now() - 3600_000,
    orgs: [],
    scopes: []
  };

  const wikiPayload = {
    schema: 'architecture.wiki-dossier:v1',
    graph: graphId,
    context: snapId,
    bundleSha256: 'sha256:' + 'f'.repeat(64),
    totalPages: 2,
    totalCommunities: 2,
    pages: [
      {
        path: 'index.md',
        title: 'Architecture Overview',
        bytes: 420,
        sha256: 'sha256:' + 'e'.repeat(64),
        content: [
          '# Architecture Overview',
          '',
          '> Multi-module architecture summary with XSS probe <img src=x onerror=window.__xss=1>.',
          '',
          '## Analyzed Modules',
          '',
          '| Module | Types | Communities |',
          '| --- | ---: | ---: |',
          '| `orders` | 4 | [Open Orders Community](communities/orders.md) |',
          '| `billing` | 3 | `a | b` pipe-in-code |',
          '',
          '- Deterministic Leiden modularity `Q = 0.5412` and SQL_TABLE_MAPS_JPA_ENTITY link',
          '- Zero host build execution with [bad](javascript:alert(1)) and [proto](//evil.test/x)',
          '',
          '```java',
          'public class OrderService {}',
          '```'
        ].join('\n')
      },
      {
        path: 'communities/orders.md',
        title: 'orders · OrderService',
        bytes: 310,
        sha256: 'sha256:' + 'd'.repeat(64),
        content: [
          '# Community: orders · OrderService',
          '',
          'Return to [Architecture Overview](../index.md).',
          '',
          '## Members',
          '',
          '| Class / Symbol | Source Path |',
          '| --- | --- |',
          '| `com.acme.orders.OrderService` | `src/main/java/com/acme/orders/OrderService.java` |',
          '| `com.acme.orders.OrderEntity` | `src/main/java/com/acme/orders/OrderEntity.java` |'
        ].join('\n')
      }
    ]
  };

  const mcpSetupPayload = {
    protocolVersion: '2025-11-25',
    workbenchPort: 8080,
    multiUserMode: true,
    patMintSupported: true,
    toolCount: 19,
    workbenchProxyConfig: {
      mcpServers: {
        'evo-workbench': {
          command: 'java',
          args: ['-cp', 'D:/resolved/path/backend.jar', 'com.evolution.backend.query.WorkbenchMcpMain', '--port', '8080'],
          env: { EVO_MCP_TOKEN: '<set-your-evo-pat-here>' }
        }
      }
    },
    directOfflineConfig: {
      mcpServers: {
        'evo-offline': {
          command: 'java',
          args: ['-cp', 'D:/resolved/path/backend.jar', 'com.evolution.backend.query.WorkbenchMcpMain', '--store', 'D:/resolved/real-store']
        }
      }
    },
    resources: [
      { uri: 'evo://score-v2', description: 'Architecture Health Score v2' },
      { uri: 'evo://report', description: 'Executive architecture report with Leiden communities' },
      { uri: 'evo://gaps', description: 'Closed capability gaps denominator' },
      { uri: 'evo://hotspots', description: 'Hub hotspots & high-coupling types' },
      { uri: 'evo://cycles', description: 'Tarjan dependency cycles' },
      { uri: 'evo://spring-summary', description: 'Spring conditional wiring summary' }
    ],
    agentRuleSnippet: 'Always consult Evo MCP before refactoring.'
  };

  const adHocOutcome = {
    version: 'architecture.repair-proposal:captured-v1',
    status: 'PROPOSED',
    sources: [{ path: 'src/main/java/com/acme/orders/OrderEntity.java', digest: 'sha256:' + '5'.repeat(64) }],
    gaps: [],
    syntax: [],
    attempts: 1,
    advisoryContext: [
      {
        kind: 'SQL_TABLE_MAPS_JPA_ENTITY',
        label: 'orders -> com.acme.orders.OrderEntity',
        sourcePath: 'src/main/resources/db/migration/V1__orders.sql',
        startLine: 1,
        endLine: 4,
        matchBasis: '@Table(name="orders")',
        trust: 'ADVISORY_UNVERIFIED'
      },
      {
        kind: 'DOC_MENTIONS_SYMBOL',
        label: 'ADR-001 § Orders Storage',
        sourcePath: 'docs/adr/ADR-001.md',
        startLine: 3,
        endLine: 12,
        matchBasis: 'backtick-fqn',
        trust: 'ADVISORY_UNVERIFIED'
      }
    ],
    leidenContext: [
      {
        communityId: 'comm:orders',
        communityLabel: 'orders · OrderService',
        dominantPackage: 'com.acme.orders',
        memberCount: 4,
        misplaced: true,
        bridgeCount: 2
      }
    ],
    proposal: {
      identity: propId,
      candidate: {
        baseline: { graph: graphId, snapshot: snapId, context: ctxId },
        strategy: 'AI',
        edits: [{ path: 'src/main/java/com/acme/orders/OrderEntity.java', before: 'sha256:' + '5'.repeat(64), after: 'sha256:' + '6'.repeat(64) }],
        patchDigest: 'sha256:' + '7'.repeat(64),
        patch: [
          'diff --git a/src/main/java/com/acme/orders/OrderEntity.java b/src/main/java/com/acme/orders/OrderEntity.java',
          '--- a/src/main/java/com/acme/orders/OrderEntity.java',
          '+++ b/src/main/java/com/acme/orders/OrderEntity.java',
          '@@ -1,3 +1,3 @@',
          ' public class OrderEntity {',
          '-  String status = "NEW";',
          '+  String status = "PENDING";',
          ' }'
        ].join('\n'),
        verification: 'SYNTAX_ONLY'
      }
    }
  };

  function readBody(req) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      req.on('data', c => chunks.push(c));
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      req.on('error', reject);
    });
  }

  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const route = url.pathname;
    try {
      if (!route.startsWith('/api/')) {
        const name = route === '/' ? 'index.html' : route.slice(1);
        const filePath = path.join(webDir, name);
        const bytes = await readFile(filePath);
        res.setHeader('Content-Security-Policy', strictCsp);
        res.setHeader('Content-Type', mime[path.extname(name)] || 'application/octet-stream');
        res.end(bytes);
        return;
      }

      const method = req.method || 'GET';
      const rawBody = (method === 'POST' || method === 'PUT' || method === 'PATCH') ? await readBody(req) : '';
      const jsonBody = rawBody ? JSON.parse(rawBody) : {};
      res.setHeader('Content-Type', 'application/json');
      const ok = (data, status = 200) => { res.statusCode = status; res.end(JSON.stringify({ data })); };
      const err = (status, code, message) => { res.statusCode = status; res.end(JSON.stringify({ error: { code, message } })); };

      if (route === '/api/v1') {
        return ok({
          analysisJobsEnabled: true,
          analysisJobEventsEnabled: true,
          architectureScoreV2Enabled: true,
          architectureInsightsEnabled: true,
          repositoryScoringEnabled: true,
          repositoryConnectionsEnabled: true,
          evolutionEnabled: true,
          architectureChatEnabled: true,
          architectureChatAgentEnabled: true,
          repairEnabled: true,
          githubEnabled: true,
          multiUserAuthEnabled: true,
          multiUserMode: true,
          analysisModes: ['FULL'],
          operations: ['summary', 'entities', 'dependencies', 'impact', 'gaps', 'cycles', 'spring']
        });
      }
      if (route === '/api/v1/auth/config') {
        return ok({
          multiUserAuthEnabled: true,
          multiUserMode: true,
          localAccountsEnabled: true,
          hasRegisteredUsers: true,
          canConfigureGitHubSetup: true,
          manifestFlowSupported: true,
          oauthConfigured: false,
          deviceFlowEnabled: false
        });
      }
      if (route === '/api/v1/auth/me') {
        return ok({ authenticated, multiUserMode: true, user: authenticated ? user : null, csrfToken: authenticated ? csrfToken : '' });
      }
      if (route === '/api/v1/auth/tokens' && method === 'GET') {
        return ok({ items: tokens, allowedScopes: ['mcp:read', 'api:read', 'api:write'] });
      }
      if (route === '/api/v1/auth/tokens' && method === 'POST') {
        assert.equal(req.headers['x-evo-csrf'], csrfToken, 'POST /api/v1/auth/tokens MUST carry X-Evo-CSRF');
        csrfChecked.push('POST /api/v1/auth/tokens');
        const id = 'pat-' + (tokenSeq++);
        const plaintext = 'evo_pat_stage0_secret_' + id + '_' + 'z'.repeat(18);
        const scopes = Array.isArray(jsonBody.scopes) && jsonBody.scopes.length ? jsonBody.scopes : ['mcp:read'];
        if (jsonBody.label === 'IDE MCP Bridge') lastMcpMintScopes = scopes;
        const meta = {
          tokenId: id,
          userId: user.userId,
          label: String(jsonBody.label || 'Token'),
          prefix: plaintext.slice(0, 16) + '…',
          scopes,
          createdAtMs: Date.now(),
          expiresAtMs: Date.now() + Number(jsonBody.expiresInDays || 90) * 86400_000,
          lastUsedAtMs: 0,
          revoked: false,
          revokedAtMs: 0,
          expired: false
        };
        tokens.unshift(meta);
        return ok({ token: plaintext, metadata: meta, notice: 'Shown once only.' }, 201);
      }
      if (route.startsWith('/api/v1/auth/tokens/') && method === 'DELETE') {
        assert.equal(req.headers['x-evo-csrf'], csrfToken, 'DELETE /api/v1/auth/tokens/{id} MUST carry X-Evo-CSRF');
        csrfChecked.push('DELETE ' + route);
        const id = decodeURIComponent(route.slice('/api/v1/auth/tokens/'.length));
        const found = tokens.find(t => t.tokenId === id);
        if (!found) return err(404, 'TOKEN_NOT_FOUND', 'Not found');
        found.revoked = true;
        found.revokedAtMs = Date.now();
        return ok({ revoked: true, tokenId: id });
      }
      if (route === '/api/v1/auth/users') return ok({ items: [user] });
      if (route === '/api/v1/auth/github/setup') return ok({ configured: false, source: 'NONE', host: 'github.com', manifestFlowSupported: true, isLoopbackHost: true });
      if (route === '/api/v1/mcp/setup') return ok(mcpSetupPayload);
      if (route === '/api/v1/llm/config' || route === '/api/v1/ai/providers') {
        return ok({
          configured: true,
          providers: [{ id: 'ollama', configured: true, active: true, model: 'stage0-mock', endpoint: 'http://127.0.0.1:11434/v1/chat/completions', remote: false }],
          provider: 'ollama', model: 'stage0-mock', endpoint: 'http://127.0.0.1:11434/v1/chat/completions', allowRemote: false
        });
      }
      const demoJob = {
        id: 'job-1', setup: 'demo', state: 'SUCCEEDED',
        result: { completion: 'COMPLETE', graph: graphId, snapshot: snapId, contexts: [ctxId], reports: [] }
      };
      if (route.endsWith('/setups')) return ok({ items: [{ id: 'demo', repository: 'https://example.test/demo.git', release: 21 }] });
      if (route.endsWith('/connections')) return ok({ items: [{ setup: 'demo', label: 'Stage0 Demo Repo', kind: 'LOCAL', repository: 'https://example.test/demo.git' }] });
      if (route.endsWith('/history')) return ok({ items: [demoJob] });
      if (route === '/api/v1/analysis/jobs') return ok({ items: [demoJob], jobs: [demoJob] });
      if (route === '/api/v1/analysis/jobs/job-1') return ok(demoJob);
      if (route === '/api/v1/analysis/jobs/job-1/coverage') return ok({ job: 'job-1', status: 'COMPLETE', total: 0, items: [] });
      if (route === `/api/v1/graphs/${graphId}`) {
        return ok({
          identity: graphId,
          snapshot: snapId,
          contexts: [
            { identity: snapId, family: 'repository.snapshot', nodeCount: 10 },
            { identity: ctxId, family: 'analysis.manifest', modulePath: 'orders', sourceSet: 'MAIN', kind: 'java', nodeCount: 12 }
          ],
          reports: []
        });
      }
      if (route.endsWith('/wiki-dossier')) return ok(wikiPayload);
      if (route.endsWith('/architecture-score') || route.endsWith('/score-v2')) {
        return ok({
          graph: graphId, context: snapId, scopeMode: 'WHOLE_REPOSITORY',
          formula: { id: 'architecture.score-formula', version: 'v2.0' },
          status: 'COMPLETE', score: 88.5, conservativeLowerBound: 88.5, uncertaintyDelta: 0,
          band: 'Strong', confidence: { score: 100, tier: 'HIGH', reasons: [] }, dimensions: []
        });
      }
      if (route.endsWith('/repository-score')) return ok({ status: 'COMPLETE', score: 100, checks: [] });
      if (route.endsWith('/architecture-insights') || route.endsWith('/queries/insights')) {
        return ok({
          graph: graphId, context: ctxId, scope: 'TYPE',
          summary: { analyzedEntities: 4, dependencyEdges: 5, tangleCount: 0, tangledEntities: 0, sdpViolations: 0, communityCount: 2 },
          communityModularity: 0.5412, communities: [], misplacedTypes: [], communityBridges: [], tangles: [], hotspots: [], sdpViolations: [], recommendations: []
        });
      }
      if (route.endsWith('/advisory-links')) {
        return ok({ schema: 'advisory.doc-link:v1', derivation: 'INFERRED', trust: 'ADVISORY_UNVERIFIED', totalNodes: 2, totalLinks: 2, nodeCounts: {}, linkCounts: {}, nodes: [], links: [] });
      }
      if (route.endsWith('/repair-proposal/ad-hoc') && method === 'POST') {
        return ok(adHocOutcome);
      }
      if (route.endsWith('/chat') && method === 'POST') {
        return ok({
          version: 'architecture.chat:v1',
          status: 'ADVICE',
          pin: { graph: graphId, snapshot: snapId, context: ctxId, finding: null },
          evidenceIdentity: graphId,
          conversationTitle: 'Stage0 Tools Verification',
          answer: 'Inspected architecture brief, advisory SQL/ADR links, markdown ADR section, and CODEOWNERS [E1].',
          citations: ['E1'],
          evidence: [{ id: 'E1', kind: 'SUMMARY', label: 'Architecture Summary', identity: ctxId, data: { status: 'COMPLETE' } }],
          limitations: [],
          agentTrace: [
            { step: 1, tool: 'architecture_brief', arguments: { graph: graphId }, summary: 'Loaded snapshot-pinned executive brief', evidenceIds: ['E1'], durationMs: 12 },
            { step: 2, tool: 'inspect_advisory_links', arguments: { graph: graphId }, summary: '2 advisory links (SQL + ADR)', evidenceIds: ['E1'], durationMs: 9 },
            { step: 3, tool: 'read_markdown_section', arguments: { path: 'docs/adr/ADR-001.md' }, summary: 'Read ADR-001 § Orders Storage', evidenceIds: ['E1'], durationMs: 7 },
            { step: 4, tool: 'inspect_codeowners', arguments: { graph: graphId }, summary: 'Matched @orders-team', evidenceIds: ['E1'], durationMs: 5 }
          ]
        });
      }
      if (route.endsWith('/queries/summary')) return ok({ status: 'COMPLETE', items: [{ nodeCounts: { TYPE: 4, MODULE: 1 }, edgeCounts: { 'graph.depends-on': 5 } }], gaps: [] });
      if (route.endsWith('/queries/provenance')) return ok({ items: [{ family: 'analysis.manifest', metadata: { modulePath: 'orders', sourceSet: 'MAIN' } }] });
      if (route.endsWith('/queries/visual-graph')) return ok({ id: 'vg-1', nodes: [], edges: [], metadata: {} });
      if (route.endsWith('/queries/entities') || route.endsWith('/queries/dependencies') || route.endsWith('/queries/gaps') || route.endsWith('/queries/cycles') || route.endsWith('/queries/spring')) {
        return ok({ status: 'COMPLETE', items: [], exactTotal: 0, gaps: [], bounds: { truncated: false } });
      }
      if (route.endsWith('/codeowners')) return ok({ found: false, rules: [] });
      if (route === '/api/v1/github/status') return ok({ configured: false, multiUserMode: true });
      if (route === '/api/v1/github/repos') return ok({ items: [] });
      if (route === '/api/v1/repairs/jobs') return ok({ items: [] });
      return ok({});
    } catch (e) {
      res.statusCode = 500;
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ error: { code: 'TEST_SERVER_ERROR', message: e.message } }));
    }
  });

  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  let page;
  try {
    page = await edge(browser, output);
    const consoleErrors = [];
    const pageErrors = [];
    const cspViolations = [];
    page.socket.addEventListener('message', evt => {
      try {
        const msg = JSON.parse(evt.data);
        if (msg.method === 'Runtime.consoleAPICalled' && msg.params?.type === 'error') {
          consoleErrors.push((msg.params.args || []).map(a => a.value || a.description || '').join(' '));
        } else if (msg.method === 'Runtime.exceptionThrown') {
          pageErrors.push(msg.params?.exceptionDetails?.text || 'exception');
        } else if (msg.method === 'Log.entryAdded' && (msg.params?.entry?.source === 'security'
            || /Content Security Policy/i.test(msg.params?.entry?.text || ''))) {
          cspViolations.push(msg.params.entry.text);
        }
      } catch (_) {}
    });
    await page.send('Runtime.enable');
    await page.send('Log.enable');
    await page.send('Page.addScriptToEvaluateOnNewDocument', {
      source: `window.__stage0Csp = []; document.addEventListener('securitypolicyviolation', e => window.__stage0Csp.push(e.violatedDirective + ':' + e.blockedURI));`
    });
    await page.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
    await page.send('Page.navigate', { url: origin });
    const ev = expr => page.evaluate(expr);

    await until('Workbench published workspace loaded and authenticated', () =>
      ev("document.querySelector('#analysis-workspace')?.hidden === false && Boolean(document.getElementById('open-wiki-dossier')) && Boolean(window.EvoGitHubAuth?.isAuthenticated?.())"));

    // -------------------------------------------------------------------------
    // 1. Rendered XSS-Safe Markdown Wiki Modal + Clickable Relative Link + 320px/1440px Overflow
    // -------------------------------------------------------------------------
    await ev("document.getElementById('open-wiki-dossier').click()");
    await until('Rendered Wiki Markdown article loaded', () =>
      ev("document.getElementById('wiki-dossier-dialog').open && Boolean(document.querySelector('#wiki-dossier-body article.evo-wiki-md h1'))"));

    const wikiInitial = await ev(`(() => {
      const md = document.querySelector('#wiki-dossier-body article.evo-wiki-md');
      return {
        hasH1: Boolean(md.querySelector('h1.evo-wiki-h1')),
        hasH2: Boolean(md.querySelector('h2.evo-wiki-h2')),
        hasTable: Boolean(md.querySelector('table.evo-wiki-table')),
        hasCode: Boolean(md.querySelector('pre.evo-code-block')),
        hasList: Boolean(md.querySelector('ul.evo-wiki-list li')),
        hasQuote: Boolean(md.querySelector('blockquote.evo-wiki-quote')),
        hasImgTag: md.querySelectorAll('img').length,
        xssExecuted: Boolean(window.__xss),
        inlineLinks: [...md.querySelectorAll('button.evo-wiki-inline-link')].map(b => b.textContent)
      };
    })()`);
    assert.equal(wikiInitial.hasH1, true, 'Wiki modal must render <h1>');
    assert.equal(wikiInitial.hasH2, true, 'Wiki modal must render <h2>');
    assert.equal(wikiInitial.hasTable, true, 'Wiki modal must render Markdown table');
    assert.equal(wikiInitial.hasCode, true, 'Wiki modal must render fenced code block');
    assert.equal(wikiInitial.hasList, true, 'Wiki modal must render list');
    assert.equal(wikiInitial.hasQuote, true, 'Wiki modal must render blockquote');
    assert.equal(wikiInitial.hasImgTag, 0, 'XSS <img> tag must NEVER be parsed as DOM');
    assert.equal(wikiInitial.xssExecuted, false, 'XSS onerror must NEVER execute');
    assert.ok(wikiInitial.inlineLinks.includes('Open Orders Community'), 'Relative wiki link must render');

    // Verify snake_case preservation (no mangled <em> inside SQL_TABLE_MAPS_JPA_ENTITY), pipe-in-code table cell,
    // blocked javascript:/protocol-relative links, and WCAG AA contrast in BOTH Dark AND Light themes!
    const wikiDeep = await ev(`(() => {
      const md = document.querySelector('#wiki-dossier-body .evo-wiki-md');
      const emTexts = [...md.querySelectorAll('em')].map(e => e.textContent);
      const billingRowCells = [...md.querySelectorAll('tbody tr')[1].querySelectorAll('td')].map(td => td.textContent.trim());
      const jsLinks = [...md.querySelectorAll('a')].filter(a => /javascript:|evil\\.test/i.test(a.getAttribute('href') || ''));
      const parseRgba = s => {
        const m = String(s || '').match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)(?:,\\s*([\\d.]+))?\\)/);
        return m ? [Number(m[1]), Number(m[2]), Number(m[3]), m[4] == null ? 1 : Number(m[4])] : [0, 0, 0, 0];
      };
      const blend = (fg, bg) => {
        const a = fg[3];
        return [fg[0]*a + bg[0]*(1-a), fg[1]*a + bg[1]*(1-a), fg[2]*a + bg[2]*(1-a), 1];
      };
      const effectiveBg = node => {
        const layers = [];
        let cur = node;
        while (cur && cur.nodeType === 1) {
          const c = parseRgba(getComputedStyle(cur).backgroundColor);
          if (c[3] > 0) layers.push(c);
          if (c[3] >= 0.99) break;
          cur = cur.parentElement;
        }
        let base = [15, 23, 42, 1];
        for (let i = layers.length - 1; i >= 0; i--) base = blend(layers[i], base);
        return base;
      };
      const lum = ([r, g, b]) => {
        const t = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
        return 0.2126 * t(r) + 0.7152 * t(g) + 0.0722 * t(b);
      };
      const ratio = el => {
        const fg = parseRgba(getComputedStyle(el).color);
        const bg = effectiveBg(el);
        const l1 = lum(fg), l2 = lum(bg);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
      };
      const auditTheme = theme => {
        if (theme === 'light') document.documentElement.setAttribute('data-theme', 'light');
        else document.documentElement.removeAttribute('data-theme');
        return {
          mdText: Number(ratio(md).toFixed(2)),
          navActive: Number(ratio(document.querySelector('#wiki-dossier-body .evo-wiki-nav-btn.is-active')).toFixed(2)),
          inlineLink: Number(ratio(document.querySelector('#wiki-dossier-body .evo-wiki-inline-link')).toFixed(2)),
          tableCell: Number(ratio(md.querySelector('tbody td')).toFixed(2))
        };
      };
      const darkContrast = auditTheme('dark');
      const lightContrast = auditTheme('light');
      document.documentElement.removeAttribute('data-theme');
      return {
        emTexts,
        hasSnakeVerbatim: md.textContent.includes('SQL_TABLE_MAPS_JPA_ENTITY'),
        billingRowCells,
        jsLinksCount: jsLinks.length,
        darkContrast,
        lightContrast
      };
    })()`);
    assert.equal(wikiDeep.hasSnakeVerbatim, true, 'Snake-case identifier must be preserved verbatim');
    assert.deepEqual(wikiDeep.emTexts, [], 'Snake-case underscores must NOT create broken <em> tags: ' + JSON.stringify(wikiDeep.emTexts));
    assert.equal(wikiDeep.billingRowCells.length, 3, 'Pipe inside backtick code must NOT split table cell: ' + JSON.stringify(wikiDeep.billingRowCells));
    assert.ok(wikiDeep.billingRowCells[2].includes('a | b'), 'Pipe inside inline code must stay intact: ' + JSON.stringify(wikiDeep.billingRowCells));
    assert.equal(wikiDeep.jsLinksCount, 0, 'javascript: and protocol-relative // links must NEVER become <a>');
    for (const [themeName, c] of [['dark', wikiDeep.darkContrast], ['light', wikiDeep.lightContrast]]) {
      for (const [k, v] of Object.entries(c)) {
        assert.ok(v >= 4.5, `WCAG AA contrast failed in ${themeName} theme for Wiki ${k}: ${v} < 4.5 (${JSON.stringify(c)})`);
      }
    }

    // Click relative Markdown link [Open Orders Community](communities/orders.md) -> switches page inside modal!
    await ev("document.querySelector('#wiki-dossier-body button.evo-wiki-inline-link').click()");
    await until('Wiki modal switched to communities/orders.md', () =>
      ev("document.querySelector('#wiki-dossier-body .evo-wiki-preview').innerText.includes('com.acme.orders.OrderService')"));
    const wikiSwitched = await ev(`(() => {
      const activeNav = document.querySelector('#wiki-dossier-body .evo-wiki-nav-btn.is-active');
      const txt = document.querySelector('#wiki-dossier-body .evo-wiki-preview').innerText;
      return {
        activeNavText: activeNav?.textContent || '',
        hasPath: txt.includes('src/main/java/com/acme/orders/OrderService.java'),
        overflow1440: document.documentElement.scrollWidth > window.innerWidth + 1
      };
    })()`);
    assert.ok(wikiSwitched.activeNavText.includes('communities/orders.md'), 'Nav button must update active state: ' + wikiSwitched.activeNavText);
    assert.equal(wikiSwitched.hasPath, true, 'Community page must display readable file path');
    assert.equal(wikiSwitched.overflow1440, false, 'Zero overflow at 1440px with Wiki modal open');

    // Click ../index.md relative parent link -> switches back to index.md!
    await ev("document.querySelector('#wiki-dossier-body button.evo-wiki-inline-link[data-wiki-target=\"index.md\"]').click()");
    await until('Wiki modal navigated back via ../index.md', () =>
      ev("document.querySelector('#wiki-dossier-body .evo-wiki-nav-btn.is-active').textContent.includes('index.md')"));

    // 320px responsive check while Wiki modal is open
    await page.send('Emulation.setDeviceMetricsOverride', { width: 320, height: 800, deviceScaleFactor: 1, mobile: true });
    const wiki320 = await ev("({ scrollW: document.documentElement.scrollWidth, innerW: window.innerWidth })");
    assert.ok(wiki320.scrollW <= wiki320.innerW + 1, `Wiki modal overflowed at 320px: ${wiki320.scrollW} > ${wiki320.innerW}`);
    await page.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
    await ev("document.getElementById('wiki-dossier-dialog').close()");

    // -------------------------------------------------------------------------
    // 2. Multi-User Scoped PAT Mint, One-Time Reveal, Revoke in Account Settings
    // -------------------------------------------------------------------------
    await ev("window.EvoGitHubAuth.openWorkspaceAccountModal('tokens')");
    await until('API & MCP Tokens tab rendered', () =>
      ev("Boolean(document.getElementById('ws-pat-create-btn'))"));

    await ev(`(() => {
      const inp = document.getElementById('ws-pat-label');
      inp.value = 'Stage0 IDE PAT';
      inp.dispatchEvent(new Event('input', { bubbles: true }));
      document.getElementById('ws-pat-create-btn').click();
    })()`);
    await until('Minted PAT banner shown once', () =>
      ev("Boolean(document.getElementById('ws-pat-minted-banner')) && document.getElementById('ws-pat-minted-banner').innerText.includes('evo_pat_')"));

    const patState = await ev(`(() => {
      const secret = document.querySelector('#ws-pat-minted-banner .evo-pat-secret-code')?.textContent || '';
      const listText = document.getElementById('ws-pat-list')?.innerText || '';
      return {
        secretStartsPat: secret.startsWith('evo_pat_'),
        listContainsLabel: listText.includes('Stage0 IDE PAT'),
        listLeaksPlaintext: secret.length > 0 && listText.includes(secret),
        overflow1440: document.documentElement.scrollWidth > window.innerWidth + 1
      };
    })()`);
    assert.equal(patState.secretStartsPat, true, 'One-time reveal must show evo_pat_... plaintext');
    assert.equal(patState.listContainsLabel, true, 'Token list must display newly minted label');
    assert.equal(patState.listLeaksPlaintext, false, 'Token list MUST NOT show plaintext secret');
    assert.equal(patState.overflow1440, false, 'Zero overflow at 1440px in PAT tab');

    // WCAG AA contrast check on PAT reveal banner & secret code in Dark & Light themes
    const patContrast = await ev(`(() => {
      const parseRgba = s => {
        const m = String(s || '').match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)(?:,\\s*([\\d.]+))?\\)/);
        return m ? [Number(m[1]), Number(m[2]), Number(m[3]), m[4] == null ? 1 : Number(m[4])] : [0, 0, 0, 0];
      };
      const blend = (fg, bg) => { const a = fg[3]; return [fg[0]*a + bg[0]*(1-a), fg[1]*a + bg[1]*(1-a), fg[2]*a + bg[2]*(1-a), 1]; };
      const effectiveBg = node => {
        const layers = []; let cur = node;
        while (cur && cur.nodeType === 1) {
          const c = parseRgba(getComputedStyle(cur).backgroundColor);
          if (c[3] > 0) layers.push(c);
          if (c[3] >= 0.99) break;
          cur = cur.parentElement;
        }
        let base = [15, 23, 42, 1];
        for (let i = layers.length - 1; i >= 0; i--) base = blend(layers[i], base);
        return base;
      };
      const lum = ([r, g, b]) => {
        const t = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
        return 0.2126 * t(r) + 0.7152 * t(g) + 0.0722 * t(b);
      };
      const ratio = el => { const l1 = lum(parseRgba(getComputedStyle(el).color)), l2 = lum(effectiveBg(el)); return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05); };
      const check = theme => {
        if (theme === 'light') document.documentElement.setAttribute('data-theme', 'light');
        else document.documentElement.removeAttribute('data-theme');
        return {
          banner: Number(ratio(document.querySelector('#ws-pat-minted-banner strong')).toFixed(2)),
          code: Number(ratio(document.querySelector('#ws-pat-minted-banner .evo-pat-secret-code')).toFixed(2))
        };
      };
      const d = check('dark'), l = check('light');
      document.documentElement.removeAttribute('data-theme');
      return { dark: d, light: l };
    })()`);
    for (const [themeName, c] of [['dark', patContrast.dark], ['light', patContrast.light]]) {
      for (const [k, v] of Object.entries(c)) {
        assert.ok(v >= 4.5, `WCAG AA contrast failed in ${themeName} theme for PAT ${k}: ${v} < 4.5 (${JSON.stringify(c)})`);
      }
    }

    // 320px check on Account Settings PAT tab
    await page.send('Emulation.setDeviceMetricsOverride', { width: 320, height: 800, deviceScaleFactor: 1, mobile: true });
    const pat320 = await ev("({ scrollW: document.documentElement.scrollWidth, innerW: window.innerWidth })");
    assert.ok(pat320.scrollW <= pat320.innerW + 1, `PAT tab overflowed at 320px: ${pat320.scrollW} > ${pat320.innerW}`);
    await page.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

    // Revoke token -> CSRF verified + status badge updates to Revoked; closing dialog wipes plaintext PAT banner!
    await ev("document.querySelector('#ws-pat-list button[data-revoke-token]').click()");
    await until('Token status updated to Revoked', () =>
      ev("document.getElementById('ws-pat-list').innerText.includes('Revoked')"));
    await ev("document.querySelector('dialog[open]')?.close()");
    await until('Account Settings dialog close wiped plaintext PAT banner from DOM', () =>
      ev("!document.getElementById('github-auth-dialog').textContent.includes('evo_pat_stage0_secret_')"));

    // -------------------------------------------------------------------------
    // 3. Connect IDE (MCP) Modal: Real Resolved Paths + 1-Click PAT Helper
    // -------------------------------------------------------------------------
    await ev("document.getElementById('open-mcp-setup').click()");
    await until('MCP modal opened with 1-click PAT helper', () =>
      ev("document.getElementById('mcp-setup-dialog').open && document.getElementById('mcp-setup-body').innerText.includes('1-Click Mint Read-Only MCP Token')"));
    const mcpCheck = await ev(`(() => {
      const txt = document.getElementById('mcp-setup-body').innerText;
      return {
        hasLiteralEvoJar: txt.includes('"evo.jar"'),
        hasLiteralStorePlaceholder: txt.includes('<path-to-evo-store>'),
        hasResolvedStore: txt.includes('D:/resolved/real-store'),
        has19Tools: txt.includes('19')
      };
    })()`);
    assert.equal(mcpCheck.hasLiteralEvoJar, false, 'MCP setup must not show literal evo.jar');
    assert.equal(mcpCheck.hasLiteralStorePlaceholder, false, 'MCP setup must not show <path-to-evo-store>');
    assert.equal(mcpCheck.hasResolvedStore, true, 'MCP setup must show real resolved store path');

    await ev("Array.from(document.querySelectorAll('#mcp-setup-body button')).find(b => b.textContent.includes('1-Click Mint Read-Only MCP Token')).click()");
    await until('MCP 1-click PAT minted and revealed', () =>
      ev("document.getElementById('mcp-setup-body').innerText.includes('evo_pat_stage0_secret_')"));
    assert.deepEqual(lastMcpMintScopes, ['mcp:read'], '1-click MCP helper must request read-only mcp:read scope');
    await ev("document.getElementById('mcp-setup-dialog').close()");
    await until('Ephemeral PAT plaintext cleared from DOM on dialog close', () =>
      ev("!document.getElementById('mcp-setup-body').textContent.includes('evo_pat_stage0_secret_')"));

    // -------------------------------------------------------------------------
    // 4. Repair Studio: Blank Focus Files Placeholder + Leiden & Advisory Badges
    // -------------------------------------------------------------------------
    await ev(`(() => {
      const host = document.createElement('div');
      host.id = 'stage0-rs-host';
      document.body.appendChild(host);
      window.EvoRepairStudio.mount(host, {
        env: {
          graph: ${JSON.stringify(graphId)},
          context: ${JSON.stringify(ctxId)},
          signal: new AbortController().signal
        },
        findings: [],
        capabilities: { repairEnabled: true },
        api: async (p, body, sig, m) => {
          const r = await fetch('/api/v1' + p, {
            method: m || (body ? 'POST' : 'GET'),
            headers: body ? { 'Content-Type': 'application/json', 'X-Evo-CSRF': ${JSON.stringify(csrfToken)} } : {},
            body: body ? JSON.stringify(body) : undefined
          });
          const j = await r.json();
          return j.data;
        }
      });
    })()`);
    await until('Repair Studio mounted', () =>
      ev("Boolean(document.querySelector('#stage0-rs-host textarea, #stage0-rs-host input'))"));
    const rsStep1 = await ev(`(() => {
      const inputs = Array.from(document.querySelectorAll('#stage0-rs-host input, #stage0-rs-host textarea'));
      const focusInput = inputs.find(i => (i.placeholder || '').toLowerCase().includes('repo map') || (i.placeholder || '').toLowerCase().includes('repomap'));
      const titleInput = inputs.find(i => (i.placeholder || '').toLowerCase().includes('decouple') || (i.placeholder || '').toLowerCase().includes('issue') || (i.placeholder || '').toLowerCase().includes('title'));
      if (titleInput) {
        titleInput.value = 'Normalize OrderEntity status';
        titleInput.dispatchEvent(new Event('input', { bubbles: true }));
      }
      const descInput = document.querySelector('#stage0-rs-host textarea');
      if (descInput) {
        descInput.value = 'Change OrderEntity status from NEW to PENDING';
        descInput.dispatchEvent(new Event('input', { bubbles: true }));
      }
      const startBtn = Array.from(document.querySelectorAll('#stage0-rs-host button')).find(b => /Propose|Continue|Generate|Repair/i.test(b.textContent || ''));
      return { hasRepoMapPlaceholder: Boolean(focusInput), startBtnText: startBtn?.textContent || '' };
    })()`);
    assert.equal(rsStep1.hasRepoMapPlaceholder, true, 'Blank Focus Files input must explain automatic RepoMap seeding');

    // Submit ad-hoc repair with blank focusFiles -> Step 2 renders Leiden + Advisory SQL/ADR badges!
    await ev("Array.from(document.querySelectorAll('#stage0-rs-host button')).find(b => (b.textContent || '').includes('Generate Ad-Hoc Patch')).click()");
    await until('Repair Studio rendered Leiden & Advisory badges', () =>
      ev("document.getElementById('stage0-rs-host').innerText.includes('SQL DDL:') && document.getElementById('stage0-rs-host').innerText.includes('orders · OrderService')"));
    const rsCheck = await ev(`(() => {
      const host = document.getElementById('stage0-rs-host');
      const titles = [...host.querySelectorAll('.rs-intel-badges .badge')].map(el => el.title || '').join(' | ');
      return { text: host.textContent, titles };
    })()`);
    assert.ok(rsCheck.text.toLowerCase().includes('sql ddl: orders'), 'Flyway SQL advisory badge must render in Repair Studio: ' + rsCheck.text);
    assert.ok(rsCheck.titles.includes('V1__orders.sql'), 'Flyway SQL path must be in badge title: ' + rsCheck.titles);
    assert.ok(rsCheck.text.toLowerCase().includes('adr/doc: adr-001'), 'ADR advisory badge must render in Repair Studio: ' + rsCheck.text);
    assert.ok(rsCheck.titles.includes('ADR-001.md'), 'ADR path must be in badge title: ' + rsCheck.titles);
    assert.ok(rsCheck.text.includes('orders · OrderService'), 'Leiden community badge must render in Repair Studio');
    assert.ok(rsCheck.text.includes('Misplaced'), 'Misplaced type badge must render in Repair Studio');

    // -------------------------------------------------------------------------
    // 5. AI Chat: Load lazy architecture-chat.js and verify all 4 new toolMeta Labels
    //    (architecture_brief, inspect_advisory_links, read_markdown_section, inspect_codeowners)
    // -------------------------------------------------------------------------
    await ev(`new Promise((resolve, reject) => {
      if (typeof window.mountEvoArchitectureChat === 'function') return resolve();
      const s = document.createElement('script');
      s.src = '/architecture-chat.js';
      s.onload = () => resolve();
      s.onerror = () => reject(new Error('Failed to load /architecture-chat.js'));
      document.head.appendChild(s);
    })`);
    await ev(`(() => {
      const host = document.createElement('div');
      host.id = 'stage0-chat-host';
      document.body.appendChild(host);
      const chatNode = window.mountEvoArchitectureChat({
        graph: ${JSON.stringify(graphId)},
        snapshot: ${JSON.stringify(snapId)},
        context: ${JSON.stringify(ctxId)},
        provider: { model: 'stage0-mock', endpoint: 'http://127.0.0.1:11434/v1/chat/completions', remote: false },
        agentEnabled: true,
        send: async (query, sig) => {
          const r = await fetch('/api/v1/graphs/' + ${JSON.stringify(graphId)} + '/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Evo-CSRF': ${JSON.stringify(csrfToken)} },
            body: JSON.stringify(query),
            signal: sig
          });
          const j = await r.json();
          return j.data;
        },
        signal: new AbortController().signal
      });
      host.appendChild(chatNode);
    })()`);
    await until('AI Chat mounted', () => ev("Boolean(document.querySelector('#stage0-chat-host textarea'))"));
    await ev(`(() => {
      const ta = document.querySelector('#stage0-chat-host textarea');
      ta.value = 'Inspect brief, advisory links, ADR section, and CODEOWNERS';
      ta.dispatchEvent(new Event('input', { bubbles: true }));
      const form = document.querySelector('#stage0-chat-host form');
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    })()`);
    await until('AI Chat rendered agent trace with all 4 new toolMeta entries', () =>
      ev("document.getElementById('stage0-chat-host').textContent.includes('CODEOWNERS')"));
    const chatTrace = await ev("document.getElementById('stage0-chat-host').textContent");
    for (const expectedLabel of ['Architecture Brief', 'Advisory Links', 'Read Markdown Section', 'CODEOWNERS']) {
      assert.ok(chatTrace.includes(expectedLabel),
        `Missing new toolMeta label "${expectedLabel}" in chat trace: ${chatTrace}`);
    }

    assert.ok(csrfChecked.includes('POST /api/v1/auth/tokens'), 'PAT mint must be CSRF-checked');
    assert.ok(csrfChecked.some(c => c.startsWith('DELETE /api/v1/auth/tokens/')), 'PAT revoke must be CSRF-checked');
    const domCsp = await ev("window.__stage0Csp || []");
    assert.deepEqual(domCsp, [], 'Zero DOM securitypolicyviolation events allowed: ' + JSON.stringify(domCsp));
    assert.deepEqual(cspViolations, [], 'Zero CSP violations allowed under strict zero-https CSP: ' + JSON.stringify(cspViolations));
    assert.deepEqual(pageErrors, [], 'Zero uncaught page errors allowed: ' + JSON.stringify(pageErrors));
    assert.deepEqual(consoleErrors, [], 'Zero console errors allowed: ' + JSON.stringify(consoleErrors));

    console.log('PASS stage0-web-ux.browser.cjs (strict zero-https CSP, rendered XSS-safe Wiki Markdown + clickable inter-page links, Account Settings PAT mint/reveal/revoke + CSRF, MCP 1-click PAT + resolved paths, Repair Studio Leiden/Advisory badges + RepoMap placeholder, AI Chat 4 new toolMeta labels, 320px/1440px zero overflow)');
  } finally {
    await page?.close().catch(() => {});
    await new Promise(resolve => server.close(resolve));
  }
}

main(process.argv.slice(2)).catch(err => {
  console.error(err);
  process.exit(1);
});
