'use strict';

/**
 * Headless Edge Browser E2E Verification for Waves 1, 2 & 3 Web App Surfaces:
 * - Zero console/page/CSP errors under strict zero-https CSP
 * - Dependency Map: Leiden Communities (GroupBy.COMMUNITY) super-nodes, Collapse/Expand all,
 *   Drift / Bridges / Tangles layer pills, Offline Export PNG & Export SVG byte validation
 * - Review -> Insights: Emergent Leiden Communities, Architectural Drift (Misplaced Types), Bridges
 * - Architecture -> Docs, SQL & IaC: Quarantined advisory.doc-link:v1 (ADVISORY / UNVERIFIED),
 *   SQL<->JPA, ADR, Terraform, MCP env-only links, and inline Markdown section reader
 * - Topbar Tools -> Architecture Wiki / Dossier multi-page preview modal & .zip/.md downloads
 * - Topbar Tools -> Connect IDE (MCP) modal with Proxy + Offline configs & all 6 evo:// resources
 * - GitHub Hub -> Leiden PR Community-Overlap Risk card (semantic-only peer-PR overlap)
 */

const assert = require('node:assert/strict');
const {createServer} = require('node:http');
const {mkdir, readFile} = require('node:fs/promises');
const path = require('node:path');
const {edge, until} = require('./m9-edge.cjs');

async function main([browser, output]) {
  const directory = path.resolve(__dirname, '../../main/resources/web');
  const mime = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.svg': 'image/svg+xml',
    '.woff2': 'font/woff2'
  };
  const strictCsp = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";

  const graphId = 'sha256:' + 'a'.repeat(64);
  const snapId = 'snapshot:sha256:' + 'b'.repeat(64);
  const ctxId = 'analysis:core';
  const repoId = 'repo:sha256:' + 'c'.repeat(64);
  const date = new Date(0).toISOString();

  const seenVisualRequests = [];
  const seenMarkdownRequests = [];
  const sockets = [];

  const visualPackage = {
    id: 'sha256:' + '1'.repeat(64),
    nodes: [
      {id: 'compound:pkg:com.acme.billing', label: 'com.acme.billing', kind: 'PACKAGE', properties: {compound: true}},
      {id: 'compound:pkg:com.acme.orders', label: 'com.acme.orders', kind: 'PACKAGE', properties: {compound: true}},
      {id: 't:InvoiceService', label: 'InvoiceService', kind: 'TYPE', parent: 'compound:pkg:com.acme.billing', origin: 'PROJECT', properties: {declaredPackage: 'com.acme.billing'}},
      {id: 't:TaxCalculator', label: 'TaxCalculator', kind: 'TYPE', parent: 'compound:pkg:com.acme.billing', origin: 'PROJECT', properties: {declaredPackage: 'com.acme.billing'}},
      {id: 't:OrderService', label: 'OrderService', kind: 'TYPE', parent: 'compound:pkg:com.acme.orders', origin: 'PROJECT', properties: {declaredPackage: 'com.acme.orders'}},
      {id: 't:LegacyHelper', label: 'LegacyHelper', kind: 'TYPE', parent: 'compound:pkg:com.acme.orders', origin: 'PROJECT', properties: {declaredPackage: 'com.acme.orders'}}
    ],
    edges: [
      {id: 'e1', source: 't:InvoiceService', target: 't:TaxCalculator', kinds: ['graph.depends-on'], classification: 'MUST', properties: {}},
      {id: 'e2', source: 't:OrderService', target: 't:InvoiceService', kinds: ['graph.depends-on'], classification: 'MUST', properties: {}},
      {id: 'e3', source: 't:LegacyHelper', target: 't:InvoiceService', kinds: ['graph.depends-on'], classification: 'MAY', properties: {}}
    ],
    metadata: {scope: 'TYPE', projection: 'BOUNDED_UNION', status: 'COMPLETE', nodeCount: 4, edgeCount: 3}
  };

  const visualCommunity = {
    id: 'sha256:' + '2'.repeat(64),
    nodes: [
      {
        id: 'compound:community:comm:billing', label: 'billing · InvoiceService', kind: 'COMMUNITY',
        properties: {
          compound: true, communityId: 'comm:billing', memberCount: 3, cohesion: 0.78,
          dominantPackage: 'com.acme.billing', hubLabel: 'InvoiceService', misplacedCount: 1, bridgeEdgeCount: 1
        }
      },
      {
        id: 'compound:community:comm:orders', label: 'orders · OrderService', kind: 'COMMUNITY',
        properties: {
          compound: true, communityId: 'comm:orders', memberCount: 1, cohesion: 1.0,
          dominantPackage: 'com.acme.orders', hubLabel: 'OrderService', misplacedCount: 0, bridgeEdgeCount: 1
        }
      },
      {
        id: 'compound:external', label: 'External Dependencies', kind: 'EXTERNAL_GROUP', origin: 'DEPENDENCY',
        properties: { compound: true, externalGroup: true, memberCount: 1 }
      },
      {id: 't:InvoiceService', label: 'InvoiceService', kind: 'TYPE', parent: 'compound:community:comm:billing', origin: 'PROJECT', properties: {communityId: 'comm:billing', declaredPackage: 'com.acme.billing', misplaced: false}},
      {id: 't:TaxCalculator', label: 'TaxCalculator', kind: 'TYPE', parent: 'compound:community:comm:billing', origin: 'PROJECT', properties: {communityId: 'comm:billing', declaredPackage: 'com.acme.billing', misplaced: false}},
      {id: 't:LegacyHelper', label: 'LegacyHelper', kind: 'TYPE', parent: 'compound:community:comm:billing', origin: 'PROJECT', properties: {communityId: 'comm:billing', declaredPackage: 'com.acme.orders', misplaced: true, dominantPackage: 'com.acme.billing'}},
      {id: 't:OrderService', label: 'OrderService', kind: 'TYPE', parent: 'compound:community:comm:orders', origin: 'PROJECT', properties: {communityId: 'comm:orders', declaredPackage: 'com.acme.orders', misplaced: false}},
      {id: 't:String', label: 'java.lang.String', kind: 'TYPE', parent: 'compound:external', origin: 'JDK', properties: {declaredPackage: 'java.lang', misplaced: false, externalUnclustered: true}}
    ],
    edges: [
      {id: 'e1', source: 't:InvoiceService', target: 't:TaxCalculator', kinds: ['graph.depends-on'], classification: 'MUST', properties: {isBridge: false}},
      {id: 'e2', source: 't:OrderService', target: 't:InvoiceService', kinds: ['graph.depends-on'], classification: 'MUST', properties: {isBridge: true, sourceCommunity: 'comm:orders', targetCommunity: 'comm:billing'}},
      {id: 'e3', source: 't:LegacyHelper', target: 't:InvoiceService', kinds: ['graph.depends-on'], classification: 'MAY', properties: {isBridge: false}},
      {id: 'e4', source: 't:OrderService', target: 't:String', kinds: ['graph.depends-on'], classification: 'MUST', properties: {isBridge: false}}
    ],
    metadata: {
      scope: 'TYPE', projection: 'BOUNDED_UNION', status: 'COMPLETE', groupBy: 'COMMUNITY',
      communityAlgorithm: 'leiden-cpm-modularity-v1', communityModularity: 0.4821,
      communityCount: 2, externalNodeCount: 1, misplacedCount: 1, bridgeEdgeCount: 1
    }
  };

  const insightsPayload = {
    scope: 'TYPE',
    entityCount: 4,
    edgeCount: 3,
    tangles: [],
    hotspots: [{identity: 't:InvoiceService', label: 'InvoiceService', archetype: 'HUB', hotspotScore: 42, transitiveDependents: 2, fanIn: 2, fanOut: 1, degree: 3}],
    couplingMatrix: {cells: [], orderedGroups: []},
    sdpViolations: [],
    packageMetrics: [{group: 'com.acme.billing', entityCount: 2, abstractCount: 0, ca: 2, ce: 0, instability: 0.0, abstractness: 0.0, distance: 1.0, zone: 'ZONE_OF_PAIN'}],
    summary: {analyzedEntities: 4, internalDependencies: 3, tangleCount: 0, maxBlastRadius: 2},
    recommendations: [],
    gaps: [],
    markdownReport: '# Architecture Insights\n',
    communityModularity: 0.4821,
    communities: [
      {id: 'comm:billing', label: 'billing · InvoiceService', dominantPackage: 'com.acme.billing', hubIdentity: 't:InvoiceService', hubLabel: 'InvoiceService', memberCount: 3, cohesion: 0.78, misplacedCount: 1, bridgeEdgeCount: 1, members: ['t:InvoiceService', 't:TaxCalculator', 't:LegacyHelper']},
      {id: 'comm:orders', label: 'orders · OrderService', dominantPackage: 'com.acme.orders', hubIdentity: 't:OrderService', hubLabel: 'OrderService', memberCount: 1, cohesion: 1.0, misplacedCount: 0, bridgeEdgeCount: 1, members: ['t:OrderService']}
    ],
    misplacedTypes: [
      {identity: 't:LegacyHelper', label: 'LegacyHelper', declaredPackage: 'com.acme.orders', communityId: 'comm:billing', communityLabel: 'billing · InvoiceService', dominantPackage: 'com.acme.billing', internalCommunityEdges: 3, declaredPackageEdges: 0, rationale: 'Tightly coupled to com.acme.billing'}
    ],
    communityBridges: [
      {fromCommunityId: 'comm:orders', fromCommunityLabel: 'orders · OrderService', toCommunityId: 'comm:billing', toCommunityLabel: 'billing · InvoiceService', edgeCount: 1, totalWeight: 1.0, sampleEdges: ['OrderService -> InvoiceService']}
    ]
  };

  const advisoryPayload = {
    schema: 'advisory.doc-link:v1',
    derivation: 'INFERRED',
    trust: 'ADVISORY_UNVERIFIED',
    graph: graphId,
    totalNodes: 4,
    totalLinks: 2,
    redactedSecretsCount: 2,
    nodesTruncated: false,
    linksTruncated: false,
    nodeCounts: {ADR_DOCUMENT: 1, SQL_TABLE: 1, TF_RESOURCE: 1, MCP_ENV_VAR: 1},
    linkCounts: {DOC_MENTIONS_SYMBOL: 1, SQL_TABLE_MAPS_JPA_ENTITY: 1},
    nodes: [
      {id: 'adv:adr1', kind: 'ADR_DOCUMENT', label: 'ADR-0012 Ledger Commerce Settlement Reconciliation Boundary Contract', sourcePath: 'docs/architecture/decisions/ADR-0012__ledger_commerce_settlement_reconciliation_boundary_contract.md', startLine: 1, endLine: 20, derivation: 'INFERRED', trust: 'ADVISORY_UNVERIFIED'},
      {id: 'adv:sql1', kind: 'SQL_TABLE', label: 'public.order_entities', sourcePath: 'db/V1__orders.sql', startLine: 1, endLine: 6, derivation: 'INFERRED', trust: 'ADVISORY_UNVERIFIED'},
      {id: 'adv:tf1', kind: 'TF_RESOURCE', label: 'aws_db_instance.orders_db', sourcePath: 'infra/main.tf', startLine: 1, endLine: 5, derivation: 'INFERRED', trust: 'ADVISORY_UNVERIFIED'},
      {id: 'adv:mcp1', kind: 'MCP_ENV_VAR', label: 'EVO_MCP_SESSION_TOKEN', sourcePath: '.mcp.json', startLine: 6, endLine: 6, derivation: 'INFERRED', trust: 'ADVISORY_UNVERIFIED'}
    ],
    links: [
      {id: 'advl:1', kind: 'DOC_MENTIONS_SYMBOL', sourceId: 'adv:adr1', sourceLabel: 'docs/architecture/decisions/ADR-0012__ledger_commerce_settlement_reconciliation_boundary_contract.md § Orders Boundary', targetId: 't:OrderService', targetLabel: 'com.acme.orders.OrderService', targetKind: 'TYPE', sourcePath: 'docs/architecture/decisions/ADR-0012__ledger_commerce_settlement_reconciliation_boundary_contract.md', startLine: 4, endLine: 4, matchBasis: 'backtick-fqn', derivation: 'INFERRED', trust: 'ADVISORY_UNVERIFIED'},
      {id: 'advl:2', kind: 'SQL_TABLE_MAPS_JPA_ENTITY', sourceId: 'adv:sql1', sourceLabel: 'public.ledger_commerce_settlement_reconciliation_entries', targetId: 't:OrderService', targetLabel: 'com.ledger.commerce.reconciliation.LegacySettlementReconcilerServiceImpl', targetKind: 'TYPE', sourcePath: 'src/main/resources/db/migration/V001__initial_ledger_commerce_settlement_reconciliation_schema.sql', startLine: 1, endLine: 42, matchBasis: '@Table(name="ledger_commerce_settlement_reconciliation_entries")', derivation: 'INFERRED', trust: 'ADVISORY_UNVERIFIED'}
    ]
  };

  const wikiPayload = {
    schema: 'architecture.wiki-dossier:v1',
    graph: graphId,
    context: ctxId,
    bundleSha256: 'sha256:' + 'f'.repeat(64),
    totalPages: 2,
    totalCommunities: 2,
    pages: [
      {path: 'index.md', title: 'Architecture Overview', bytes: 120, sha256: 'sha256:' + 'e'.repeat(64), content: '# Architecture Overview\n\n2 Leiden communities detected (Q = 0.4821).\n'},
      {path: 'suggested-questions.md', title: 'Suggested Questions', bytes: 140, sha256: 'sha256:' + 'd'.repeat(64), content: '# Suggested Questions\n\n1. Why is LegacyHelper coupled to com.acme.billing?\n'}
    ]
  };

  const mcpSetupPayload = {
    protocolVersion: '2025-11-25',
    workbenchPort: 8080,
    multiUserMode: false,
    workbenchProxyConfig: {mcpServers: {'evo-workbench': {command: 'java', args: ['-cp', 'evo.jar', 'com.evolution.backend.query.WorkbenchMcpMain', '--port', '8080']}}},
    directOfflineConfig: {mcpServers: {'evo-offline': {command: 'java', args: ['-cp', 'evo.jar', 'com.evolution.backend.query.WorkbenchMcpMain', '--store', '.evo/store']}}},
    resources: [
      {uri: 'evo://score-v2', description: 'Architecture Health Score v2'},
      {uri: 'evo://report', description: 'Executive architecture report'},
      {uri: 'evo://gaps', description: 'Closed capability gaps denominator'},
      {uri: 'evo://hotspots', description: 'Hub hotspots & high-coupling types'},
      {uri: 'evo://cycles', description: 'Tarjan dependency cycles'},
      {uri: 'evo://spring-summary', description: 'Spring conditional wiring summary'}
    ],
    agentRuleSnippet: 'Always consult Evo MCP before refactoring.'
  };

  const scoreV2 = {
    graph: graphId, context: snapId, scopeMode: 'WHOLE_REPOSITORY',
    formula: {id: 'architecture.score-formula', version: 'v2.0'},
    modelIdentity: 'sha256:' + '7'.repeat(64), calibration: 'EMPIRICALLY_GROUNDED_PROVISIONAL',
    status: 'COMPLETE', score: 84.2, conservativeLowerBound: 84.2, uncertaintyDelta: 0,
    healthBand: 'Healthy', applicableWeight: 100, dimensions: [],
    confidence: {confidenceScore: 95, tier: 'HIGH', evidenceTier: 'BYTECODE_AND_SOURCE', parseCompleteness: 1, internalEdgeCompleteness: 1, overallSemanticResolution: 1, buildCompleteness: 1, springSpaceDefiniteness: 1, openGapCount: 0, gapBuckets: {}, limitations: []},
    inferredLayers: {style: 'LAYERED', layerPackages: {}}, contextBreakdown: [], policyReport: null, gaps: [], reasons: []
  };

  const job = {
    id: 'job-wave123', setup: 'demo', state: 'SUCCEEDED', stage: 'FINISHED', revision: 1, mode: 'FULL',
    elapsedMillis: 1500, submittedAt: date, finishedAt: date, events: [{stage: 'FINISHED', at: date}],
    result: {graph: graphId, snapshot: snapId, contexts: [snapId, ctxId], gapCount: 0, reports: []}
  };

  const server = createServer(async (req, res) => {
    const urlObj = new URL(req.url, 'http://127.0.0.1');
    const route = urlObj.pathname === '/' ? '/index.html' : urlObj.pathname;
    try {
      if (!route.startsWith('/api/')) {
        res.setHeader('Content-Security-Policy', strictCsp);
        res.setHeader('Content-Type', mime[path.extname(route)] || 'application/octet-stream');
        res.end(await readFile(path.join(directory, route.slice(1))));
        return;
      }
      // Enforce real ArchitectureHttpServer.getRawPath() contract: percent-encoded '%3A' in graph IDs is rejected 400!
      if (route.startsWith('/api/v1/graphs/') && route.includes('%3A')) {
        res.writeHead(400, {'Content-Type': 'application/json'});
        res.end(JSON.stringify({error: {code: 'INVALID_REQUEST', message: 'Percent-encoded colon in graph digest rejected'}}));
        return;
      }
      let body = '';
      for await (const chunk of req) body += chunk;
      const input = body ? JSON.parse(body) : null;
      let data;

      if (route === '/api/v1') {
        data = {
          analysisJobsEnabled: true, analysisJobEventsEnabled: true, architectureScoreV2Enabled: true,
          architectureInsightsEnabled: true, repositoryScoringEnabled: true, repositoryConnectionsEnabled: true,
          evolutionEnabled: true, architectureChatEnabled: true, analysisModes: ['FULL']
        };
      } else if (route === '/api/v1/auth/me') {
        data = {multiUserMode: false, authenticated: true, user: {login: 'local', role: 'ADMIN'}};
      } else if (route.endsWith('/setups')) {
        data = {items: [{id: 'demo', repository: repoId}]};
      } else if (route.endsWith('/connections')) {
        data = {items: [{setup: 'demo', label: 'Acme Shop', kind: 'LOCAL', repository: repoId}]};
      } else if (route.endsWith('/history')) {
        data = {items: [job]};
      } else if (route === `/api/v1/graphs/${graphId}`) {
        data = {
          snapshot: snapId,
          contexts: [
            {identity: snapId, family: 'repository.snapshot'},
            {identity: ctxId, family: 'analysis.manifest', modulePath: 'core', sourceSet: 'MAIN', kind: 'java'}
          ]
        };
      } else if (route.endsWith('/architecture-score')) {
        data = scoreV2;
      } else if (route.endsWith('/repository-score')) {
        data = {status: 'COMPLETE', score: 100, checks: []};
      } else if (route.endsWith('/queries/summary')) {
        data = {status: 'COMPLETE', items: [{nodeCounts: {TYPE: 4, MODULE: 1, SOURCE_DOCUMENT: 8}}]};
      } else if (route.endsWith('/queries/provenance')) {
        data = {items: [{family: 'analysis.manifest', metadata: {modulePath: 'core', sourceSet: 'MAIN'}}]};
      } else if (route.endsWith('/queries/visual-graph')) {
        seenVisualRequests.push(input);
        data = input?.arguments?.groupBy === 'COMMUNITY' ? visualCommunity : visualPackage;
      } else if (route.endsWith('/architecture-insights') || route.endsWith('/queries/insights')) {
        data = insightsPayload;
      } else if (route.endsWith('/advisory-links')) {
        data = advisoryPayload;
      } else if (route.endsWith('/markdown-section')) {
        seenMarkdownRequests.push(input);
        data = {
          mode: 'SECTION', found: true, ambiguous: false,
          path: input.path, heading: input.heading || 'Orders Boundary', level: 2,
          startLine: 3, endLine: 6, totalLines: 20, truncated: false,
          digest: 'sha256:' + '9'.repeat(64),
          content: '## Orders Boundary\nThe `com.acme.orders.OrderService` coordinates `com.ledger.commerce.reconciliation.LegacySettlementReconcilerServiceImpl::reconcilePendingBatchesDeterministically`.',
          numberedSource: '3: ## Orders Boundary\n4: The `com.acme.orders.OrderService` coordinates `com.ledger.commerce.reconciliation.LegacySettlementReconcilerServiceImpl::reconcilePendingBatchesDeterministically`.',
          redactedSecretsCount: 0, candidates: []
        };
      } else if (route.endsWith('/wiki-dossier')) {
        data = wikiPayload;
      } else if (route === '/api/v1/mcp/setup') {
        data = mcpSetupPayload;
      } else if (route.endsWith('/queries/gaps') || route.endsWith('/queries/entities')) {
        data = {status: 'COMPLETE', items: [], exactTotal: 0};
      } else if (route.endsWith('/codeowners')) {
        data = {found: false, rules: []};
      } else if (route.endsWith('/ai/providers')) {
        data = {providers: [], configured: false};
      } else if (route.startsWith('/api/v1/github/')) {
        data = {configured: false, items: []};
      } else {
        data = {};
      }

      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({data}));
    } catch (err) {
      res.writeHead(500, {'Content-Type': 'application/json'});
      res.end(JSON.stringify({error: {message: err.message}}));
    }
  });
  server.on('connection', socket => sockets.push(socket));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));

  let page;
  const consoleErrors = [];
  const pageErrors = [];
  try {
    const outDir = path.resolve(output);
    await mkdir(outDir, {recursive: true});
    page = await edge(browser, outDir);
    await page.send('Runtime.enable');
    await page.send('Log.enable');
    page.socket.addEventListener('message', raw => {
      const msg = JSON.parse(raw.data);
      if (msg.method === 'Runtime.exceptionThrown') {
        pageErrors.push(msg.params?.exceptionDetails?.exception?.description || msg.params?.exceptionDetails?.text || 'exception');
      } else if (msg.method === 'Runtime.consoleAPICalled' && msg.params?.type === 'error') {
        consoleErrors.push((msg.params.args || []).map(a => a.value || a.description || '').join(' '));
      } else if (msg.method === 'Log.entryAdded' && msg.params?.entry?.level === 'error') {
        consoleErrors.push(msg.params.entry.text || 'log-error');
      }
    });

    const evaluate = expr => page.evaluate(expr);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1400, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.send('Page.navigate', {url: `http://127.0.0.1:${server.address().port}`});

    await until('Initial workbench overview loaded with score', () =>
      evaluate("document.getElementById('panel-overview')?.innerText.includes('Architecture Health v2') && Boolean(document.getElementById('view-advisory'))"));

    // Spy on URL.createObjectURL (for offline SVG/PNG bytes) and window.cytoscape (for live tap verification)
    await evaluate(`(() => {
      window.__evoCapturedDownloads = [];
      const origCreate = URL.createObjectURL.bind(URL);
      URL.createObjectURL = blob => {
        const idx = window.__evoCapturedDownloads.length;
        window.__evoCapturedDownloads.push({ type: blob.type, size: blob.size, ready: false, text: '', headHex: '' });
        if (blob.type.includes('svg')) {
          blob.text().then(t => { window.__evoCapturedDownloads[idx].text = t; window.__evoCapturedDownloads[idx].ready = true; });
        } else {
          blob.arrayBuffer().then(buf => {
            const bytes = Array.from(new Uint8Array(buf).slice(0, 8)).map(b => b.toString(16).padStart(2, '0')).join(' ');
            window.__evoCapturedDownloads[idx].headHex = bytes;
            window.__evoCapturedDownloads[idx].ready = true;
          });
        }
        return origCreate(blob);
      };
      let origCy = window.cytoscape;
      Object.defineProperty(window, 'cytoscape', {
        configurable: true,
        get() { return origCy; },
        set(fn) {
          origCy = function(...args) {
            const inst = fn.apply(this, args);
            window.__evoLastCy = inst;
            return inst;
          };
        }
      });
    })()`);

    // 1. Dependency Map -> Switch to Communities (Leiden), Collapse/Expand, Layer Pills, Export SVG & PNG
    await evaluate("document.getElementById('tab-architecture')?.click(); document.getElementById('view-graph')?.click();");
    await until('Graph grouping selector mounted', () =>
      evaluate("Boolean(document.querySelector('select[aria-label=\"Graph grouping\"]'))"));

    await evaluate(`(() => {
      const sel = document.querySelector('select[aria-label="Graph grouping"]');
      sel.value = 'COMMUNITY';
      sel.dispatchEvent(new Event('change', { bubbles: true }));
    })()`);

    await until('Community super-node controls rendered', () =>
      evaluate("Array.from(document.querySelectorAll('#panel-graph button')).some(b => b.textContent.trim() === 'Collapse all')"));

    assert.ok(seenVisualRequests.some(r => r?.arguments?.groupBy === 'COMMUNITY'),
      'Expected POST /queries/visual-graph with arguments.groupBy=COMMUNITY');

    // Verify partial collapse (comm:orders collapsed, compound:external EXPANDED) NEVER turns external dep into a bridge,
    // AND verify interactive tap on compound:external toggles collapse/expand cleanly!
    const extInteraction = await evaluate(`(() => {
      const builtPartial = window.EvoCommunityAdvisory.buildCommunityElements(
        {
          nodes: [
            {id: 'compound:community:comm:orders', label: 'orders · OrderService', kind: 'COMMUNITY', properties: {memberCount: 1, cohesion: 1.0, misplacedCount: 0, bridgeEdgeCount: 1}},
            {id: 'compound:external', label: 'External Dependencies', kind: 'EXTERNAL_GROUP', origin: 'DEPENDENCY', properties: {externalGroup: true, memberCount: 1}},
            {id: 't:OrderService', label: 'OrderService', kind: 'TYPE', parent: 'compound:community:comm:orders', origin: 'PROJECT', properties: {communityId: 'comm:orders'}},
            {id: 't:String', label: 'java.lang.String', kind: 'TYPE', parent: 'compound:external', origin: 'JDK', properties: {externalUnclustered: true}}
          ],
          edges: [
            {id: 'e4', source: 't:OrderService', target: 't:String', classification: 'MUST', properties: {isBridge: false}}
          ]
        },
        new Set(['compound:community:comm:orders']),
        new Map(),
        n => n.label,
        n => n.label,
        false
      );
      const extAggEdge = builtPartial.elements.find(el => el.data && el.data.source === 'compound:community:comm:orders' && el.data.target === 't:String');

      // Also exercise interactive Cytoscape tap on live compound:community:comm:orders & compound:external
      const cy = window.__evoLastCy;
      let liveTapOrdersExtEdge = null;
      let liveTapOrdersExtBridgeClass = null;
      let liveTapExtCollapsed = null;
      let liveTapExtReadout = '';
      if (cy) {
        // Tap comm:orders to collapse ONLY comm:orders while compound:external stays expanded
        cy.getElementById('compound:community:comm:orders').emit('tap');
        const extEdge = cy.edges().filter(e => e.source().id() === 'compound:community:comm:orders' && e.target().id() === 't:String').first();
        liveTapOrdersExtEdge = extEdge && extEdge.length ? {
          kind: extEdge.data('kind'),
          isBridge: Boolean(extEdge.data('isBridge')),
          label: extEdge.data('label')
        } : null;
        // Toggle Bridges pill and verify external edge is NOT highlighted as a bridge
        const bridgesBtn = Array.from(document.querySelectorAll('#panel-graph button')).find(b => b.textContent.trim().startsWith('Bridges ('));
        bridgesBtn.click();
        liveTapOrdersExtBridgeClass = extEdge && extEdge.length ? extEdge.hasClass('evo-layer-bridge') : null;
        bridgesBtn.click(); // reset Bridges pill

        // Now tap compound:external directly to collapse it, check readout, and tap again to expand
        cy.getElementById('compound:external').emit('tap');
        liveTapExtCollapsed = Boolean(cy.getElementById('compound:external').data('isSuperNode'));
        liveTapExtReadout = document.querySelector('#panel-graph .graph-selection')?.textContent || '';
        cy.getElementById('compound:external').emit('tap');
        cy.getElementById('compound:community:comm:orders').emit('tap');
      }
      return {
        extAggKind: extAggEdge?.data?.kind || '',
        extAggIsBridge: Boolean(extAggEdge?.data?.isBridge),
        extAggLabel: extAggEdge?.data?.label || '',
        hasExternalGroup: builtPartial.hasExternalGroup,
        externalCollapsed: builtPartial.externalCollapsed,
        liveTapOrdersExtEdge,
        liveTapOrdersExtBridgeClass,
        liveTapExtCollapsed,
        liveTapExtReadout
      };
    })()`);
    assert.equal(extInteraction.extAggKind, 'external.dependency', 'Collapsed community -> expanded external leaf MUST stay external.dependency');
    assert.equal(extInteraction.extAggIsBridge, false, 'Collapsed community -> expanded external leaf MUST NEVER become isBridge=true');
    assert.equal(extInteraction.extAggLabel, '1 dep', 'Collapsed external edge must be labeled 1 dep, not 1 bridge');
    assert.equal(extInteraction.hasExternalGroup, true);
    assert.equal(extInteraction.externalCollapsed, false);
    assert.deepEqual(extInteraction.liveTapOrdersExtEdge, {kind: 'external.dependency', isBridge: false, label: '1 dep'},
      'Live Cytoscape tap collapsing comm:orders must emit non-bridge external.dependency edge to expanded external leaf');
    assert.equal(extInteraction.liveTapOrdersExtBridgeClass, false,
      'Bridges pill must NEVER highlight external.dependency edge');
    assert.equal(extInteraction.liveTapExtCollapsed, true,
      'Live Cytoscape tap on compound:external must collapse it into super-node');
    assert.ok(extInteraction.liveTapExtReadout.includes('external collapsed'),
      'Readout must report external collapsed state cleanly: ' + extInteraction.liveTapExtReadout);

    // Click Collapse all, Expand all, Drift, Bridges, Tangles, Export SVG, Export PNG
    const mapChecks = await evaluate(`(() => {
      const buttons = Array.from(document.querySelectorAll('#panel-graph button'));
      const find = prefix => buttons.find(b => b.textContent.trim().startsWith(prefix));
      const collapse = find('Collapse all');
      const expand = find('Expand all');
      const drift = find('Drift (1)');
      const bridges = find('Bridges (1)');
      const tangles = find('Tangles');
      const expSvg = find('Export SVG');
      const expPng = find('Export PNG');
      collapse.click();
      // Export SVG while collapsed (so compound:external super-node dashed border is verified in SVG output)
      expSvg.click();
      expand.click();
      drift.click();
      bridges.click();
      tangles.click();
      expPng.click();
      return {
        driftPressed: drift.getAttribute('aria-pressed'),
        bridgesPressed: bridges.getAttribute('aria-pressed'),
        tanglesPressed: tangles.getAttribute('aria-pressed'),
        hasAll: Boolean(collapse && expand && drift && bridges && tangles && expSvg && expPng),
        countsText: document.querySelector('#panel-graph .graph-counts')?.textContent || ''
      };
    })()`);
    assert.equal(mapChecks.hasAll, true);
    assert.equal(mapChecks.driftPressed, 'true');
    assert.equal(mapChecks.bridgesPressed, 'true');
    assert.equal(mapChecks.tanglesPressed, 'true');
    assert.ok(mapChecks.countsText.includes('1 external'), 'Community map status should display external count cleanly: ' + mapChecks.countsText);

    await until('Offline SVG & PNG blobs captured', () =>
      evaluate("window.__evoCapturedDownloads.length >= 2 && window.__evoCapturedDownloads.every(d => d.ready)"));
    const downloads = await evaluate("window.__evoCapturedDownloads");
    const svgDl = downloads.find(d => d.type.includes('svg'));
    const pngDl = downloads.find(d => d.type === 'image/png');
    assert.ok(svgDl && svgDl.text.includes('<svg xmlns="http://www.w3.org/2000/svg"'), 'SVG export must produce valid standalone SVG XML');
    assert.ok(svgDl.text.includes('stroke-dasharray="5,3"'), 'Collapsed External Dependencies bucket in SVG must render dashed external style, not indigo community style');
    assert.ok(!/https:\/\//.test(svgDl.text), 'Offline SVG must contain zero https:// URLs');
    assert.ok(pngDl && pngDl.headHex.startsWith('89 50 4e 47'), `PNG export must produce valid PNG magic bytes, got ${pngDl?.headHex}`);

    // 2. Review -> Insights: Leiden Communities, Drift & Cross-Community Bridges
    await evaluate("document.getElementById('tab-review')?.click(); document.getElementById('view-insights')?.click();");
    await until('Insights Leiden communities rendered', () =>
      evaluate("document.getElementById('panel-insights')?.innerText.includes('Emergent Leiden Communities')"));
    const insightsText = await evaluate("document.getElementById('panel-insights').innerText");
    assert.ok(insightsText.includes('billing · InvoiceService'), 'Expected Leiden community label in Insights');
    assert.ok(insightsText.includes('Architectural Drift'), 'Expected Architectural Drift table in Insights');
    assert.ok(insightsText.includes('LegacyHelper'), 'Expected misplaced type LegacyHelper in Insights');
    assert.ok(insightsText.includes('Cross-Community Bridges'), 'Expected Cross-Community Bridges table in Insights');

    // 3. Architecture -> Docs, SQL & IaC (Advisory Quarantined Overlay + Inline Markdown Section Reader)
    await evaluate("document.getElementById('tab-architecture')?.click(); document.getElementById('view-advisory')?.click();");
    await until('Advisory view loaded with quarantined links', () =>
      evaluate("document.getElementById('panel-advisory')?.innerText.includes('ADVISORY / UNVERIFIED') && document.getElementById('panel-advisory')?.innerText.includes('SQL_TABLE_MAPS_JPA_ENTITY')"));
    const advText = await evaluate("document.getElementById('panel-advisory').innerText");
    assert.ok(advText.includes('advisory.doc-link:v1'), 'Expected quarantine schema banner');
    assert.ok(advText.includes('aws_db_instance.orders_db'), 'Expected Terraform resource node');
    assert.ok(advText.includes('EVO_MCP_SESSION_TOKEN'), 'Expected MCP env var name node');

    await evaluate("Array.from(document.querySelectorAll('#panel-advisory button')).find(b => b.textContent.trim() === 'Read section').click()");
    await until('Inline Markdown section loaded', () =>
      evaluate("document.getElementById('panel-advisory')?.innerText.includes('3: ## Orders Boundary')"));
    assert.ok(seenMarkdownRequests.length >= 1, 'Expected POST /markdown-section request');

    // 3b. Mobile 320x800 zero horizontal overflow check on Advisory panel with long unbroken SQL paths & FQNs
    await page.send('Emulation.setDeviceMetricsOverride', {width: 320, height: 800, deviceScaleFactor: 1, mobile: true});
    const mobileAdvisory = await evaluate(`new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => {
      const panel = document.getElementById('panel-advisory');
      const doc = document.documentElement;
      resolve({
        panelScrollW: panel ? panel.scrollWidth : 0,
        panelClientW: panel ? panel.clientWidth : 0,
        docScrollW: doc.scrollWidth,
        innerW: window.innerWidth
      });
    })))`);
    assert.ok(mobileAdvisory.panelClientW > 0, 'Advisory panel must be visible at 320px');
    assert.ok(mobileAdvisory.panelScrollW <= mobileAdvisory.panelClientW,
      `#panel-advisory overflowed horizontally at 320px: scrollWidth=${mobileAdvisory.panelScrollW} > clientWidth=${mobileAdvisory.panelClientW}`);
    assert.ok(mobileAdvisory.docScrollW <= mobileAdvisory.innerW,
      `Document overflowed horizontally at 320px on Advisory view: scrollWidth=${mobileAdvisory.docScrollW} > innerWidth=${mobileAdvisory.innerW}`);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1400, height: 900, deviceScaleFactor: 1, mobile: false});

    // 4. Topbar Tools -> Architecture Wiki / Dossier Modal
    await evaluate("document.getElementById('open-wiki-dossier').click()");
    await until('Wiki Dossier modal opened with pages', () =>
      evaluate("document.getElementById('wiki-dossier-dialog').open && document.getElementById('wiki-dossier-body').innerText.includes('Architecture Overview')"));
    const wikiCheck = await evaluate(`(() => {
      const body = document.getElementById('wiki-dossier-body');
      const navBtns = Array.from(body.querySelectorAll('.evo-wiki-nav-btn'));
      if (navBtns.length > 1) navBtns[1].click();
      const zipLink = Array.from(body.querySelectorAll('a')).find(a => a.textContent.includes('.zip'));
      const mdLink = Array.from(body.querySelectorAll('a')).find(a => a.textContent.includes('.md'));
      return {
        pageCount: navBtns.length,
        switchedText: body.querySelector('.evo-wiki-preview')?.innerText || '',
        zipHref: zipLink?.getAttribute('href') || '',
        mdHref: mdLink?.getAttribute('href') || ''
      };
    })()`);
    assert.equal(wikiCheck.pageCount, 2);
    assert.ok(wikiCheck.switchedText.includes('Suggested Questions'));
    assert.ok(wikiCheck.zipHref.includes('format=zip'));
    assert.ok(wikiCheck.mdHref.includes('format=markdown'));
    assert.ok(!wikiCheck.zipHref.split('?')[0].includes('%3A'), 'Wiki ZIP URL path must keep sha256: colon unencoded for raw URI parsing');
    assert.ok(!wikiCheck.mdHref.split('?')[0].includes('%3A'), 'Wiki Markdown URL path must keep sha256: colon unencoded for raw URI parsing');
    await evaluate("document.getElementById('wiki-dossier-dialog').close()");

    // 5. Topbar Tools -> Connect IDE (MCP) Modal with all 6 evo:// Resources
    await evaluate("document.getElementById('open-mcp-setup').click()");
    await until('MCP Setup modal opened with 6 resources', () =>
      evaluate("document.getElementById('mcp-setup-dialog').open && document.getElementById('mcp-setup-body').innerText.includes('evo://spring-summary')"));
    const mcpText = await evaluate("document.getElementById('mcp-setup-body').innerText");
    for (const uri of ['evo://score-v2', 'evo://report', 'evo://gaps', 'evo://hotspots', 'evo://cycles', 'evo://spring-summary']) {
      assert.ok(mcpText.includes(uri), `Expected MCP resource ${uri} in setup dialog`);
    }
    await evaluate("document.getElementById('mcp-setup-dialog').close()");

    // 6. PR Community-Overlap Risk Card & Snapshot Community Evolution Card rendering checks
    const overlapRendered = await evaluate(`(() => {
      const card = window.EvoCommunityAdvisory.renderPrCommunityOverlapCard({
        touchedCommunityCount: 1,
        riskLevel: 'MEDIUM',
        summary: 'Touches 1 Leiden community with 1 semantic peer-PR overlap.',
        touchedCommunities: [{communityId: 'comm:billing', label: 'billing · InvoiceService', dominantPackage: 'com.acme.billing', cohesion: 0.78, changedFiles: ['InvoiceService.java']}],
        peerPrOverlaps: [{peerPullNumber: 42, peerTitle: 'Update VAT rates', sharedCommunityLabels: ['billing · InvoiceService'], semanticOnlyOverlap: true}]
      });
      return card ? card.innerText : '';
    })()`);
    assert.ok(overlapRendered.includes('MEDIUM RISK'));
    assert.ok(overlapRendered.includes('PR #42'));
    assert.ok(overlapRendered.includes('Semantic-only conflict'));

    const commEvoRendered = await evaluate(`(() => {
      const card = window.EvoCommunityAdvisory.renderCommunityEvolutionCard({
        stabilityIndex: 0.75,
        survivedCount: 2,
        splitCount: 1,
        mergedCount: 0,
        bornCount: 1,
        dissolvedCount: 0,
        transitions: [
          {status: 'SURVIVED', beforeLabels: ['billing'], afterLabels: ['billing'], sharedMembers: 5, jaccard: 0.83, summary: 'billing survived'},
          {status: 'SPLIT', beforeLabels: ['monolith'], afterLabels: ['orders', 'shipping'], sharedMembers: 6, jaccard: 0.5, summary: 'monolith split into 2 communities'}
        ]
      });
      return card ? card.innerText : '';
    })()`);
    assert.ok(commEvoRendered.includes('75%'));
    assert.ok(commEvoRendered.includes('SPLIT'));
    assert.ok(commEvoRendered.includes('monolith split into 2 communities'));

    assert.deepEqual(pageErrors, [], 'Expected zero uncaught page errors');
    assert.deepEqual(consoleErrors, [], 'Expected zero console/CSP errors');
    console.log('PASS community-advisory-workbench.browser.cjs (0 console/CSP/page errors; Community Super-Nodes, SVG/PNG offline export, Insights Drift/Bridges, Advisory Quarantine + Markdown Reader, Wiki Dossier, MCP 6-Resources, PR Overlap verified)');
  } finally {
    await page?.close();
    sockets.forEach(s => s.destroy());
    await new Promise(resolve => server.close(resolve));
  }
}

main(process.argv.slice(2)).catch(err => {
  console.error(err);
  process.exit(1);
});
