'use strict';

/**
 * Multi-User "Sign in with GitHub" & Market-Grade Enterprise GitHub UX E2E Suite:
 * 1. Topbar Sign-In -> 3-Tab Auth Modal -> Device Code Flow (RFC 8628) -> User Profile Pill & Rate Limit Meter.
 * 2. Strict Closure-Scoped CSRF Verification (X-Evo-CSRF asserted on every mutating call; zero token in Storage).
 * 3. Vercel/SonarCloud-Style GitHub Repo & Org Browser (Org switcher, search, Private filter, 1-click Import & Analyze).
 * 4. Enterprise GitHub Hub:
 *    - Enriched PRs & Quality Gate Badge -> Preview Inline PR Review (diff-anchored + GitHub suggestion + CODEOWNERS
 *      + safe permalink + malicious javascript: URL rejection) -> Explicit Confirm -> Publish Review & Commit Status.
 *    - SARIF 2.1.0 Download & Confirmed Code Scanning Upload.
 *    - GitHub Actions CI Workflow Generator.
 *    - Webhooks & /evo ChatOps Deliveries Log.
 * 5. 2-Step Confirmed Create GitHub Issue + CODEOWNERS & View on GitHub permalinks in Captured Source, Repair Studio & Chat.
 * 6. Multi-User Tenant Isolation (Switching @octo-dev -> @bob-reviewer wipes prior tenant state completely).
 * 7. Zero console/page errors, responsive overflow (1440x900, 390x844, 320x844), WCAG AA >= 4.5:1 Dark & Light contrast.
 */

const assert = require('node:assert/strict');
const {createServer} = require('node:http');
const {mkdir, readFile, writeFile} = require('node:fs/promises');
const path = require('node:path');
const {edge, until} = require('./m9-edge.cjs');

async function capturePng(page, filePath) {
  const {data} = await page.send('Page.captureScreenshot', {format: 'png'});
  await writeFile(filePath, Buffer.from(data, 'base64'));
}

async function waitThemeSettled(page) {
  await until('theme reveal veil finished', async () => page.evaluate(`!document.querySelector('.u219-veil')`), 4000);
  await new Promise(r => setTimeout(r, 140));
}

async function auditContrastAndOverflow(page, label) {
  return page.evaluate(`(() => {
    const parseRgb = str => {
      const m = String(str || '').match(/rgba?\\((\\d+(?:\\.\\d+)?),\\s*(\\d+(?:\\.\\d+)?),\\s*(\\d+(?:\\.\\d+)?)(?:,\\s*(\\d+(?:\\.\\d+)?))?\\)/);
      if (!m) return null;
      return { r: Number(m[1]), g: Number(m[2]), b: Number(m[3]), a: m[4] == null ? 1 : Number(m[4]) };
    };
    const lum = c => {
      const f = v => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
      return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
    };
    const blend = (fg, bg) => ({
      r: fg.r * fg.a + bg.r * (1 - fg.a),
      g: fg.g * fg.a + bg.g * (1 - fg.a),
      b: fg.b * fg.a + bg.b * (1 - fg.a),
      a: 1
    });
    const ratio = (c1, c2) => {
      const l1 = lum(c1), l2 = lum(c2);
      return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    };
    const effectiveBg = el => {
      let cur = el;
      const layers = [];
      while (cur && cur.nodeType === 1) {
        const bg = parseRgb(getComputedStyle(cur).backgroundColor);
        if (bg && bg.a > 0) layers.push(bg);
        cur = cur.parentElement;
      }
      const rootBg = parseRgb(getComputedStyle(document.documentElement).backgroundColor) || { r: 10, g: 14, b: 13, a: 1 };
      let acc = { r: rootBg.r, g: rootBg.g, b: rootBg.b, a: 1 };
      for (let i = layers.length - 1; i >= 0; i--) acc = blend(layers[i], acc);
      return acc;
    };
    const failures = [];
    const selectors = [
      '.gh-signin-btn', '.gh-user-login', '.gh-auth-method-pill', '.gh-device-code',
      '.gh-repo-card-name', '.gh-ent-title', '.gh-ent-tab.is-active', '.gh-pr-title',
      '.gh-gate-badge', '.gh-owner-pill', '.gh-permalink-link', '.gh-suggestion-head',
      '.gh-chatops-cmd', '#repository-title', '.badge'
    ];
    for (const sel of selectors) {
      for (const el of document.querySelectorAll(sel)) {
        const rect = el.getBoundingClientRect();
        if (!rect.width || !rect.height || getComputedStyle(el).visibility === 'hidden') continue;
        const cs = getComputedStyle(el);
        const fg = parseRgb(cs.color);
        if (!fg || fg.a === 0) continue;
        const bg = effectiveBg(el);
        const cr = ratio(fg.a < 1 ? blend(fg, bg) : fg, bg);
        if (cr < 4.5) {
          failures.push({ sel, text: el.textContent.trim().slice(0, 32), ratio: Number(cr.toFixed(2)), fg: cs.color, bg });
        }
      }
    }
    return {
      label: ${JSON.stringify(label)},
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth,
      overflow: document.documentElement.scrollWidth > window.innerWidth + 1,
      contrastFailures: failures
    };
  })()`);
}

async function main([browser, output, screenshotDir]) {
  const directory = path.resolve(__dirname, '../../main/resources/web');
  const mime = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2'};
  const sockets = [];
  const date = new Date(0).toISOString();

  const graphOcto = 'sha256:' + 'a'.repeat(64);
  const snapshotOcto = 'snapshot:sha256:' + 'b'.repeat(64);
  const reportOcto = 'sha256:' + 'c'.repeat(64);
  const findingOcto = 'sha256:' + 'd'.repeat(64);
  const bundleOcto = 'sha256:' + 'e'.repeat(64);

  const graphBob = 'sha256:' + '9'.repeat(64);
  const snapshotBob = 'snapshot:sha256:' + '8'.repeat(64);

  // Stateful multi-user session
  let currentSession = null; // null | { user, csrfToken, rateLimit }
  let devicePollCount = 0;
  let octoConnected = false;
  let octoAnalyzed = false;
  const csrfCheckedRoutes = [];
  const publishedEvents = [];

  const octoUser = {
    userId: 'github:octo-dev',
    login: 'octo-dev',
    name: 'Octo Developer',
    avatarUrl: '',
    email: 'octo@acme.dev',
    orgs: ['acme-corp', 'platform-eng'],
    scopes: ['read:user', 'repo', 'security_events'],
    authMethod: 'OAUTH_DEVICE',
    host: 'github.com'
  };
  const bobUser = {
    userId: 'github:bob-reviewer',
    login: 'bob-reviewer',
    name: 'Bob Reviewer',
    avatarUrl: '',
    email: 'bob@private.io',
    orgs: ['bob-private'],
    scopes: ['read:user', 'repo'],
    authMethod: 'SESSION_PAT',
    host: 'github.com'
  };

  const octoRepos = [
    {
      id: 101, fullName: 'acme-corp/payments-core', owner: 'acme-corp', name: 'payments-core',
      description: 'Mission-critical Spring Boot payments ledger & settlement API.',
      privateRepo: true, defaultBranch: 'main', language: 'Java',
      htmlUrl: 'https://github.com/acme-corp/payments-core',
      cloneUrl: 'https://github.com/acme-corp/payments-core.git',
      pushedAt: new Date(Date.now() - 1800 * 1000).toISOString(),
      stargazersCount: 42, permissions: {admin: true, push: true, pull: true}
    },
    {
      id: 102, fullName: 'acme-corp/checkout-web', owner: 'acme-corp', name: 'checkout-web',
      description: 'Storefront checkout frontend.',
      privateRepo: false, defaultBranch: 'main', language: 'TypeScript',
      htmlUrl: 'https://github.com/acme-corp/checkout-web',
      cloneUrl: 'https://github.com/acme-corp/checkout-web.git',
      pushedAt: new Date(Date.now() - 86400 * 1000).toISOString(),
      stargazersCount: 15, permissions: {admin: false, push: true, pull: true}
    },
    {
      id: 103, fullName: 'platform-eng/shared-contracts', owner: 'platform-eng', name: 'shared-contracts',
      description: 'Shared protobuf & OpenAPI schemas.',
      privateRepo: true, defaultBranch: 'main', language: 'Java',
      htmlUrl: 'https://github.com/platform-eng/shared-contracts',
      cloneUrl: 'https://github.com/platform-eng/shared-contracts.git',
      pushedAt: new Date(Date.now() - 7200 * 1000).toISOString(),
      stargazersCount: 8, permissions: {admin: false, push: false, pull: true}
    }
  ];

  const octoJob = {
    id: 'job-octo-1', setup: 'acme-payments', state: 'SUCCEEDED', stage: 'FINISHED', revision: 4, mode: 'FULL',
    submittedAt: date, finishedAt: date, elapsedMillis: 2400,
    events: [{stage: 'QUEUED', at: date}, {stage: 'ACQUISITION', at: date}, {stage: 'ANALYSIS', at: date}, {stage: 'FINISHED', at: date}],
    result: {graph: graphOcto, snapshot: snapshotOcto, contexts: [snapshotOcto, 'analysis:main'], reports: [reportOcto], completion: 'COMPLETE', gapCount: 0}
  };
  const bobJob = {
    id: 'job-bob-1', setup: 'bob-secret', state: 'SUCCEEDED', stage: 'FINISHED', revision: 2, mode: 'FULL',
    submittedAt: date, finishedAt: date, elapsedMillis: 1200,
    events: [{stage: 'FINISHED', at: date}],
    result: {graph: graphBob, snapshot: snapshotBob, contexts: [snapshotBob], reports: [], completion: 'COMPLETE', gapCount: 0}
  };

  const server = createServer(async (req, res) => {
    const urlObj = new URL(req.url, 'http://127.0.0.1');
    const route = urlObj.pathname === '/' ? '/index.html' : urlObj.pathname;
    try {
      if (!route.startsWith('/api/')) {
        res.setHeader('Content-Type', mime[path.extname(route)] || 'application/octet-stream');
        res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:");
        res.end(await readFile(path.join(directory, route.slice(1))));
        return;
      }

      let rawBody = '';
      for await (const chunk of req) rawBody += chunk;
      const input = rawBody ? JSON.parse(rawBody) : null;

      // Enforce CSRF on every authenticated mutating request (except initial login endpoints)
      const isMutation = !['GET', 'HEAD'].includes(req.method);
      const csrfExempt = route === '/api/v1/auth/github/device/start'
        || route === '/api/v1/auth/github/device/poll'
        || route === '/api/v1/github/auth/session';
      if (isMutation && currentSession && !csrfExempt) {
        const hdr = req.headers['x-evo-csrf'];
        if (hdr !== currentSession.csrfToken) {
          res.writeHead(403, {'Content-Type': 'application/json'});
          res.end(JSON.stringify({error: {code: 'CSRF_TOKEN_INVALID', message: 'Missing or invalid X-Evo-CSRF header.'}}));
          return;
        }
        csrfCheckedRoutes.push({method: req.method, route, csrf: hdr});
      }

      // Raw SARIF download endpoint
      if (route === `/api/v1/graphs/${graphOcto}/reports/${reportOcto}/sarif`) {
        res.writeHead(200, {'Content-Type': 'application/sarif+json'});
        res.end(JSON.stringify({
          version: '2.1.0',
          runs: [{tool: {driver: {name: 'Evo', rules: [{id: 'web.no-repository'}]}}, results: [{ruleId: 'web.no-repository', message: {text: 'Controller depends on repository'}}]}]
        }));
        return;
      }

      let data = {};
      if (route === '/api/v1') {
        data = {
          analysisJobsEnabled: true, analysisCoverageEnabled: true, repositoryConnectionsEnabled: true,
          architectureChatEnabled: true, architectureInsightsEnabled: true, evolutionEnabled: true,
          githubIntegrationEnabled: true, githubEnterpriseEnabled: true,
          multiUserAuthEnabled: true, oauthWebEnabled: true, deviceFlowEnabled: true,
          sarifExportEnabled: true, webhooksEnabled: true,
          architectureScoreV2Enabled: false,
          architectureChatProvider: {model: 'gpt-4o-mini', remote: true, endpoint: 'https://example.invalid/v1'}
        };
      } else if (route === '/api/v1/auth/config') {
        data = {
          multiUserMode: true, oauthWebEnabled: true, deviceFlowEnabled: true, githubAppEnabled: false,
          patEnabled: true, localOperatorFallbackEnabled: false, host: 'github.com',
          scopes: ['read:user', 'repo', 'security_events']
        };
      } else if (route === '/api/v1/auth/me') {
        data = currentSession
          ? {authenticated: true, multiUserMode: true, user: currentSession.user, csrfToken: currentSession.csrfToken, rateLimit: currentSession.rateLimit}
          : {authenticated: false, multiUserMode: true, user: null, csrfToken: ''};
      } else if (route === '/api/v1/auth/github/device/start') {
        devicePollCount = 0;
        data = {deviceFlowId: 'df-test-1', userCode: 'WDJB-MJHT', verificationUri: 'https://github.com/login/device', expiresIn: 900, interval: 1};
      } else if (route === '/api/v1/auth/github/device/poll') {
        devicePollCount++;
        if (devicePollCount < 2) {
          data = {status: 'PENDING', interval: 1};
        } else {
          currentSession = {
            user: octoUser,
            csrfToken: 'csrf-octo-111',
            rateLimit: {remaining: 4820, limit: 5000, resetEpochSeconds: Math.floor(Date.now() / 1000) + 1800}
          };
          data = {status: 'AUTHORIZED', user: octoUser, csrfToken: 'csrf-octo-111'};
        }
      } else if (route === '/api/v1/github/auth/session' && req.method === 'POST') {
        assert.ok(input?.token && input.token.length >= 6);
        currentSession = {
          user: bobUser,
          csrfToken: 'csrf-bob-222',
          rateLimit: {remaining: 4990, limit: 5000, resetEpochSeconds: Math.floor(Date.now() / 1000) + 3000}
        };
        data = {authenticated: true, user: bobUser, csrfToken: 'csrf-bob-222', login: bobUser.login, source: 'SESSION_PAT'};
      } else if (route === '/api/v1/auth/logout' || route === '/api/v1/auth/logout-all') {
        currentSession = null;
        data = {loggedOut: true};
      } else if (route === '/api/v1/github/auth/status') {
        data = currentSession
          ? {authenticated: true, login: currentSession.user.login, source: currentSession.user.authMethod, rateLimitRemaining: currentSession.rateLimit.remaining, rateLimitLimit: currentSession.rateLimit.limit}
          : {authenticated: false, source: 'NONE', rateLimitRemaining: 60, rateLimitLimit: 60};
      } else if (route === '/api/v1/github/orgs') {
        data = {items: [{id: 1, login: 'acme-corp'}, {id: 2, login: 'platform-eng'}]};
      } else if (route === '/api/v1/github/user/repos') {
        const org = urlObj.searchParams.get('org') || '';
        const q = (urlObj.searchParams.get('q') || '').toLowerCase();
        const vis = urlObj.searchParams.get('visibility') || 'all';
        const pool = currentSession?.user?.login === 'bob-reviewer'
          ? [{id: 201, fullName: 'bob-private/secret-svc', owner: 'bob-private', name: 'secret-svc', privateRepo: true, defaultBranch: 'main', language: 'Go', htmlUrl: 'https://github.com/bob-private/secret-svc', cloneUrl: 'https://github.com/bob-private/secret-svc.git', pushedAt: date, permissions: {admin: true, push: true, pull: true}}]
          : octoRepos;
        const filtered = pool.filter(r => {
          if (org && r.owner !== org) return false;
          if (vis === 'private' && !r.privateRepo) return false;
          if (vis === 'public' && r.privateRepo) return false;
          if (q && !r.fullName.toLowerCase().includes(q)) return false;
          return true;
        });
        data = {page: 1, hasMore: false, items: filtered};
      } else if (route === '/api/v1/analysis/setups') {
        if (!currentSession) {
          res.writeHead(401, {'Content-Type': 'application/json'});
          res.end(JSON.stringify({error: {code: 'AUTH_REQUIRED', message: 'Sign in required'}}));
          return;
        }
        if (currentSession.user.login === 'bob-reviewer') {
          data = {items: [{id: 'bob-secret', repository: 'https://github.com/bob-private/secret-svc'}]};
        } else if (currentSession.user.login === 'octo-dev' && octoConnected) {
          data = {items: [{id: 'acme-payments', repository: 'https://github.com/acme-corp/payments-core'}]};
        } else {
          data = {items: []};
        }
      } else if (route === '/api/v1/analysis/connections' && req.method === 'GET') {
        if (!currentSession) {
          res.writeHead(401, {'Content-Type': 'application/json'});
          res.end(JSON.stringify({error: {code: 'AUTH_REQUIRED', message: 'Sign in required'}}));
          return;
        }
        if (currentSession.user.login === 'bob-reviewer') {
          data = {items: [{setup: 'bob-secret', label: 'bob-private/secret-svc', kind: 'GIT_HTTPS', provider: 'GITHUB', source: 'https://github.com/bob-private/secret-svc'}]};
        } else if (currentSession.user.login === 'octo-dev' && octoConnected) {
          data = {items: [{setup: 'acme-payments', label: 'acme-corp/payments-core', kind: 'GIT_HTTPS', provider: 'GITHUB', source: 'https://github.com/acme-corp/payments-core'}]};
        } else {
          data = {items: []};
        }
      } else if (route === '/api/v1/analysis/connections' && req.method === 'POST') {
        octoConnected = true;
        publishedEvents.push({type: 'connect', source: input.source});
        data = {setup: 'acme-payments', label: 'acme-corp/payments-core', kind: 'GIT_HTTPS', provider: 'GITHUB'};
      } else if (route === '/api/v1/analysis/jobs/history') {
        if (!currentSession) {
          res.writeHead(401, {'Content-Type': 'application/json'});
          res.end(JSON.stringify({error: {code: 'AUTH_REQUIRED', message: 'Sign in required'}}));
          return;
        }
        if (currentSession.user.login === 'bob-reviewer') data = {items: [bobJob]};
        else if (currentSession.user.login === 'octo-dev' && octoAnalyzed) data = {items: [octoJob]};
        else data = {items: []};
      } else if (route === '/api/v1/analysis/jobs' && req.method === 'POST') {
        octoAnalyzed = true;
        data = octoJob;
      } else if (route === '/api/v1/github/connections/acme-payments/metadata') {
        data = {
          fullName: 'acme-corp/payments-core', owner: 'acme-corp', repo: 'payments-core',
          private: true, defaultBranch: 'main', primaryLanguage: 'Java',
          headSha: 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2', host: 'github.com'
        };
      } else if (route === '/api/v1/github/connections/acme-payments/branches') {
        data = {items: [{name: 'main'}, {name: 'feature/decouple-orders'}]};
      } else if (route === '/api/v1/github/connections/acme-payments/tags') {
        data = {items: [{name: 'v2.1.0'}]};
      } else if (route === '/api/v1/github/connections/acme-payments/pulls') {
        data = {items: [{number: 42, title: 'Decouple OrderController from OrderRepository', headRef: 'feature/decouple-orders', baseRef: 'main'}]};
      } else if (route === '/api/v1/github/connections/acme-payments/pulls/enriched') {
        data = {
          items: [{
            number: 42,
            title: 'Decouple OrderController from OrderRepository',
            headRef: 'feature/decouple-orders',
            baseRef: 'main',
            headSha: 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2',
            author: {login: 'octo-dev', name: 'Octo Developer'},
            draft: false,
            ciState: 'passing',
            qualityGate: {verdict: 'FAILED', scoreDelta: -4.2},
            changedFiles: 4,
            updatedAt: new Date(Date.now() - 600 * 1000).toISOString(),
            htmlUrl: 'https://github.com/acme-corp/payments-core/pull/42'
          }]
        };
      } else if (route === '/api/v1/github/connections/acme-payments/pulls/42/review/preview') {
        assert.equal(Object.prototype.hasOwnProperty.call(input || {}, 'context'), false, 'review/preview MUST NOT send unexpected "context" key');
        data = {
          confirmationToken: 'rev-tok-42',
          reviewPlanDigest: 'sha256:' + '7'.repeat(64),
          commitSha: 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2',
          suggestedEvent: 'REQUEST_CHANGES',
          bodyMarkdown: '### Evo Architecture Quality Gate: FAILED\n\n- 1 new MUST violation in `OrderController.java`.',
          qualityGate: {
            verdict: 'FAILED',
            summary: '1 new MUST architecture violation blocks merge',
            conditions: [
              {metric: 'New MUST violations', actual: 1, threshold: '0', status: 'FAILED', passed: false},
              {metric: 'Architecture Score v2 delta', actual: '-4.2', threshold: '>= -2.0', status: 'FAILED', passed: false},
              {metric: 'New capability gaps', actual: 0, threshold: '0', status: 'PASSED', passed: true}
            ]
          },
          comments: [
            {
              path: 'src/main/java/com/acme/payments/OrderController.java',
              startLine: 11, line: 12, side: 'RIGHT',
              ruleId: 'web.no-repository', severity: 'ERROR',
              owners: ['@payments-team', '@alice'],
              permalink: 'https://github.com/acme-corp/payments-core/blob/a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2/src/main/java/com/acme/payments/OrderController.java#L11-L12',
              hasSuggestion: true,
              body: 'Web controller directly injects `OrderRepository`, bypassing the application service boundary.\n\n```suggestion\n  private final OrderGateway gateway;\n```'
            },
            {
              path: 'src/main/java/com/acme/payments/OrderService.java',
              line: 28, side: 'RIGHT',
              ruleId: 'layer.hint', severity: 'WARN',
              owners: ['@payments-team'],
              // Malicious URL must NEVER be rendered as an <a href>
              permalink: 'javascript:alert(1)',
              body: 'Consider declaring OrderGateway as a narrow port interface.'
            }
          ]
        };
      } else if (route === '/api/v1/github/connections/acme-payments/pulls/42/review/publish') {
        assert.equal(input.confirmedByUser, true);
        assert.equal(input.confirmationToken, 'rev-tok-42');
        publishedEvents.push({type: 'review-publish', event: input.event});
        data = {
          reviewId: 901,
          htmlUrl: 'https://github.com/acme-corp/payments-core/pull/42#pullrequestreview-901',
          state: input.event || 'REQUEST_CHANGES',
          inlineCommentsPosted: 2,
          commitStatusPosted: Boolean(input.postCommitStatus),
          qualityGateVerdict: 'FAILED'
        };
      } else if (route.includes('/commits/') && route.endsWith('/status')) {
        assert.equal(input.confirmedByUser, true);
        publishedEvents.push({type: 'commit-status', state: input.state});
        data = {state: input.state, context: input.context};
      } else if (route === '/api/v1/github/connections/acme-payments/code-scanning/sarif') {
        assert.equal(input.confirmedByUser, true);
        publishedEvents.push({type: 'sarif-upload', ref: input.ref});
        data = {
          sarifId: 'sarif-upload-777',
          url: 'https://github.com/acme-corp/payments-core/security/code-scanning',
          ruleCount: 1, resultCount: 1,
          sarifDigest: 'sha256:' + '5'.repeat(64)
        };
      } else if (route === '/api/v1/github/connections/acme-payments/ci-workflow') {
        data = {
          filename: '.github/workflows/evo-architecture-gate.yml',
          sha256: '4'.repeat(64),
          yaml: [
            'name: Evo Architecture Quality Gate',
            'on:',
            '  pull_request:',
            '    branches: [main]',
            'jobs:',
            '  evo-gate:',
            '    runs-on: ubuntu-latest',
            '    steps:',
            '      - uses: actions/checkout@v4',
            `      - run: evo gate --fail-on-must=${urlObj.searchParams.get('failOnMust') || 'true'} --min-score=${urlObj.searchParams.get('minScore') || '60'} --sarif evo.sarif`
          ].join('\n')
        };
      } else if (route === '/api/v1/github/webhooks/deliveries') {
        data = {
          webhookConfigured: true,
          items: [
            {
              deliveryId: 'deliv-9981234', event: 'issue_comment', action: 'created',
              repository: 'acme-corp/payments-core', pullNumber: 42,
              chatOpsCommand: '/evo analyze', signatureVerified: true, status: 'PROCESSED',
              receivedAtMs: Date.now() - 180000
            },
            {
              deliveryId: 'deliv-9981100', event: 'pull_request', action: 'synchronize',
              repository: 'acme-corp/payments-core', pullNumber: 42,
              chatOpsCommand: '', signatureVerified: true, status: 'QUEUED',
              receivedAtMs: Date.now() - 420000
            }
          ]
        };
      } else if (route === '/api/v1/github/connections/acme-payments/issues/prepare') {
        data = {
          confirmationToken: 'issue-tok-108',
          issuePlanDigest: 'sha256:' + '6'.repeat(64),
          title: '[Evo] Break forbidden dependency in OrderController',
          labels: ['architecture', 'must-violation', 'evo'],
          assignees: ['octo-dev'],
          codeowners: ['@payments-team', '@alice'],
          bodyMarkdown: '### Finding Summary\n\n`OrderController` directly accesses `OrderRepository`.'
        };
      } else if (route === '/api/v1/github/connections/acme-payments/issues/publish') {
        assert.equal(input.confirmedByUser, true);
        assert.equal(input.confirmationToken, 'issue-tok-108');
        publishedEvents.push({type: 'issue-publish', title: input.titleOverride});
        data = {
          issueNumber: 108,
          htmlUrl: 'https://github.com/acme-corp/payments-core/issues/108',
          title: input.titleOverride,
          labels: input.labelsOverride,
          assignees: input.assigneesOverride
        };
      } else if (route === `/api/v1/graphs/${graphOcto}/codeowners`) {
        data = {
          found: true,
          sourcePath: '.github/CODEOWNERS',
          ruleCount: 2,
          rules: [
            {line: 1, pattern: '*', owners: ['@platform-core']},
            {line: 2, pattern: '/src/main/java/com/acme/payments/', owners: ['@payments-team', '@alice']}
          ]
        };
      } else if (route === `/api/v1/graphs/${graphOcto}`) {
        data = {
          graph: graphOcto, snapshot: snapshotOcto,
          contexts: [
            {identity: snapshotOcto, family: 'repository.snapshot', label: 'Whole repository'},
            {identity: 'analysis:main', family: 'analysis.manifest', label: 'payments-core (Java)'}
          ],
          reports: [reportOcto]
        };
      } else if (route === `/api/v1/graphs/${graphBob}`) {
        data = {
          graph: graphBob, snapshot: snapshotBob,
          contexts: [{identity: snapshotBob, family: 'repository.snapshot', label: 'Whole repository'}],
          reports: []
        };
      } else if (route === `/api/v1/graphs/${graphOcto}/reports/${reportOcto}`) {
        data = {identity: reportOcto, context: 'analysis:main', findingCount: 1, hasAssessment: true};
      } else if (route === `/api/v1/graphs/${graphOcto}/reports/${reportOcto}/findings`) {
        data = {
          items: [{
            identity: findingOcto, bundleIdentity: bundleOcto,
            result: {
              classification: 'MUST', kind: 'FORBIDDEN_DEPENDENCY', status: 'VIOLATION',
              path: 'src/main/java/com/acme/payments/OrderController.java',
              rule: {id: 'web.no-repository', name: 'Web controllers must not access repositories', severity: 'ERROR'},
              bundle: {path: [], request: {scope: 'TYPE', context: 'analysis:main'}}
            }
          }]
        };
      } else if (route === `/api/v1/graphs/${graphOcto}/reports/${reportOcto}/diagnosis`) {
        data = {
          status: 'CITATIONS_VALIDATED', mode: 'LOCAL_REPLAY', input: 'diag-pin-1',
          explanation: 'OrderController directly injects OrderRepository.',
          citations: [{path: 'src/main/java/com/acme/payments/OrderController.java', span: {startLine: 11, startColumn: 3, endLine: 12, endColumn: 38}}]
        };
      } else if (route === `/api/v1/graphs/${graphOcto}/captured-source`) {
        data = {
          found: true, path: 'src/main/java/com/acme/payments/OrderController.java',
          language: 'java', startLine: 1, endLine: 16, totalLines: 16,
          content: 'package com.acme.payments;\npublic class OrderController {\n  private final OrderRepository repo;\n}'
        };
      } else if (route === `/api/v1/graphs/${graphOcto}/architecture-chat`) {
        data = {
          status: 'ADVICE',
          pin: {graph: graphOcto, context: input?.context || 'analysis:main', snapshot: snapshotOcto},
          answer: 'OrderController injects OrderRepository directly [E1].',
          citations: ['E1'],
          evidenceIdentity: 'sha256:' + '3'.repeat(64),
          evidence: [{
            id: 'E1', kind: 'SOURCE', label: 'OrderController.java:11',
            data: {path: 'src/main/java/com/acme/payments/OrderController.java', startLine: 11, endLine: 12}
          }]
        };
      } else if (route.includes('/queries/summary')) {
        data = {items: [{nodeCounts: {MODULE: 1, PACKAGE: 3, TYPE: 24}}]};
      } else if (route.includes('/queries/provenance')) {
        data = {items: [{family: 'analysis.manifest', metadata: {modulePath: 'payments-core'}}]};
      } else if (route.includes('/github/connections/bob-secret/')) {
        data = {items: [], fullName: 'bob-private/secret-svc', owner: 'bob-private', repo: 'secret-svc', defaultBranch: 'main'};
      } else {
        data = {items: []};
      }

      res.writeHead(200, {'Content-Type': 'application/json'});
      res.end(JSON.stringify({apiVersion: '1', data}));
    } catch (err) {
      res.writeHead(500, {'Content-Type': 'application/json'});
      res.end(JSON.stringify({error: {code: 'INTERNAL', message: err.message}}));
    }
  });
  server.on('connection', s => sockets.push(s));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;

  const consoleErrors = [];
  const pageErrors = [];
  let page;
  await mkdir(output, {recursive: true});
  if (screenshotDir) await mkdir(screenshotDir, {recursive: true});

  try {
    page = await edge(browser, output);
    await page.send('Runtime.enable');
    page.socket.addEventListener('message', ev => {
      try {
        const msg = JSON.parse(ev.data);
        if (msg.method === 'Runtime.consoleAPICalled' && msg.params?.type === 'error') {
          consoleErrors.push((msg.params.args || []).map(a => a.value || a.description).join(' '));
        } else if (msg.method === 'Runtime.exceptionThrown') {
          pageErrors.push(msg.params?.exceptionDetails?.exception?.description || msg.params?.exceptionDetails?.text || 'exception');
        }
      } catch (_) {}
    });
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.send('Page.navigate', {url: `http://127.0.0.1:${port}/`});

    // 1. Topbar "Sign in with GitHub" button & clean signed-out state (zero 401 calls / zero error banner!)
    await until('Sign in with GitHub button and signed-out welcome visible', async () => page.evaluate(`Boolean(document.getElementById('github-signin-trigger')) && Boolean(document.getElementById('welcome-github-signin')) && !document.getElementById('welcome-github-signin').hidden`));
    const bootNoticeHidden = await page.evaluate(`document.getElementById('notice').hidden`);
    assert.equal(bootNoticeHidden, true, 'Unauthenticated multi-user boot must NOT display an error notice');
    assert.equal(consoleErrors.length, 0, 'Unauthenticated multi-user boot must produce 0 console 401 errors: ' + consoleErrors.join('; '));

    // Open Sign-In Modal -> expand "Other sign-in options" -> Device Code tab -> Generate code
    await page.evaluate(`document.getElementById('github-signin-trigger').click()`);
    await until('Sign-In modal opened', async () => page.evaluate(`document.getElementById('github-auth-dialog').open`));
    await page.evaluate(`(() => { const d = document.getElementById('modal-other-options'); if (d && !d.open) d.querySelector('summary').click(); })()`);
    await until('Other sign-in options expanded', async () => page.evaluate(`document.getElementById('modal-other-options')?.open === true`));
    await page.evaluate(`[...document.querySelectorAll('#github-auth-dialog .gh-auth-tab')].find(b => b.textContent.includes('Device Code')).click()`);
    await until('Device start button visible', async () => page.evaluate(`Boolean(document.getElementById('github-device-start-btn'))`));
    await page.evaluate(`document.getElementById('github-device-start-btn').click()`);
    await until('Device user code displayed', async () => page.evaluate(`document.getElementById('github-device-user-code')?.textContent === 'WDJB-MJHT'`));

    // Screenshot 1: Multi-user Sign-In Device Code Dialog (Dark)
    if (screenshotDir) {
      await capturePng(page, path.join(screenshotDir, 'github-signin-device-dark.png'));
    }

    // Wait for auto-poll to authorize @octo-dev
    await until('Device flow auto-poll completed and topbar user menu rendered', async () => page.evaluate(`document.querySelector('#github-user-menu .gh-user-login')?.textContent === '@octo-dev'`), 10000);

    // Verify ZERO tokens or CSRF strings leaked into localStorage or sessionStorage!
    const storageAudit = await page.evaluate(`(() => {
      const dump = JSON.stringify({ls: {...localStorage}, ss: {...sessionStorage}});
      return {hasCsrf: dump.includes('csrf-octo'), hasToken: dump.includes('ghp_')};
    })()`);
    assert.equal(storageAudit.hasCsrf, false, 'CSRF token must NEVER be persisted in browser storage');
    assert.equal(storageAudit.hasToken, false, 'Tokens must NEVER be persisted in browser storage');

    // 2. Open User Profile Dropdown -> Click "Import GitHub Repository…" -> GitHub Repo & Org Browser
    await page.evaluate(`document.querySelector('#github-user-menu summary').click()`);
    await until('Profile menu opened with Rate Limit meter', async () => page.evaluate(`document.getElementById('github-user-menu').open && document.querySelector('#github-user-menu').textContent.includes('4,820')`));
    await page.evaluate(`[...document.querySelectorAll('#github-user-menu button')].find(b => b.textContent.includes('Import GitHub Repository')).click()`);
    await until('GitHub Repo Browser loaded 3 repos', async () => page.evaluate(`document.getElementById('connect-dialog').open && document.querySelectorAll('.gh-repo-card').length === 3`));

    // Filter by Private visibility -> 2 repos
    await page.evaluate(`[...document.querySelectorAll('.gh-vis-pills button')].find(b => b.dataset.vis === 'private').click()`);
    await until('Private filter narrowed to 2 repos', async () => page.evaluate(`document.querySelectorAll('.gh-repo-card').length === 2`));

    // Screenshot 2: GitHub Repo & Org Import Browser (Dark)
    if (screenshotDir) {
      await capturePng(page, path.join(screenshotDir, 'github-repo-picker-dark.png'));
    }

    // 1-Click "Import & Analyze" on acme-corp/payments-core
    await page.evaluate(`document.querySelector('.gh-import-btn[data-repo-full-name="acme-corp/payments-core"]').click()`);
    await until('Connected repo and rendered Enterprise GitHub toolbar', async () => page.evaluate(`!document.getElementById('connect-dialog').open && document.getElementById('github-hub-bar') && !document.getElementById('github-hub-bar').hidden && document.getElementById('github-hub-bar').textContent.includes('PRs & Quality Gate')`), 10000);
    const hasNullLiteral = await page.evaluate(`/\\bnull\\b/.test(document.getElementById('github-hub-bar')?.textContent || '')`);
    assert.equal(hasNullLiteral, false, 'GitHub hub bar must never render literal "null" text');

    // 3. Enterprise GitHub Hub: PRs & Quality Gate -> Preview Inline PR Review
    await page.evaluate(`[...document.querySelectorAll('.gh-ent-bar-btn')].find(b => b.textContent.includes('PRs & Quality Gate')).click()`);
    await until('Enriched PR #42 with ciState, scoreDelta and Quality Gate Failed rendered', async () => page.evaluate(`(() => {
      const card = document.querySelector('.gh-pr-card')?.textContent || '';
      return card.includes('#42') && card.includes('CI: passing') && card.includes('-4.2') && /FAILED/i.test(document.querySelector('.gh-gate-badge')?.textContent || '');
    })()`));
    await page.evaluate(`[...document.querySelectorAll('.gh-pr-card button')].find(b => b.textContent.includes('Preview Inline PR Review')).click()`);
    await until('Inline PR Review preview with GitHub suggestion rendered', async () => page.evaluate(`Boolean(document.querySelector('.gh-review-preview-card')) && Boolean(document.querySelector('.gh-suggestion-box'))`));

    // Verify FAILED Quality Gate conditions render red ✕ (.is-bad), NEVER green ✓ (.is-good), and suggestedEvent selected
    const qgCondAudit = await page.evaluate(`(() => {
      const bad = [...document.querySelectorAll('.gh-qg-cond.is-bad')].map(el => el.textContent.trim());
      const good = [...document.querySelectorAll('.gh-qg-cond.is-good')].map(el => el.textContent.trim());
      const selEvent = document.querySelector('.gh-review-preview-card select')?.value || '';
      return {bad, good, selEvent};
    })()`);
    assert.equal(qgCondAudit.bad.length, 2, 'Expected 2 FAILED quality gate conditions with .is-bad: ' + JSON.stringify(qgCondAudit));
    assert.ok(qgCondAudit.bad.every(t => t.startsWith('✕')), 'FAILED conditions must render ✕ icon');
    assert.equal(qgCondAudit.good.length, 1, 'Expected 1 PASSED quality gate condition with .is-good');
    assert.ok(qgCondAudit.good[0].startsWith('✓'), 'PASSED condition must render ✓ icon');
    assert.equal(qgCondAudit.selEvent, 'REQUEST_CHANGES', 'suggestedEvent REQUEST_CHANGES must pre-select review event');

    // Security check: javascript:alert(1) permalink MUST NOT appear as an <a href>!
    const jsLinkCheck = await page.evaluate(`(() => {
      const bad = [...document.querySelectorAll('a[href]')].filter(a => /^\\s*javascript:/i.test(a.getAttribute('href') || ''));
      const goodPerm = document.querySelector('.gh-review-preview-card a.gh-permalink-link')?.href || '';
      const ownerPill = document.querySelector('.gh-review-preview-card .gh-owner-pill')?.textContent || '';
      return {badCount: bad.length, goodPerm, ownerPill};
    })()`);
    assert.equal(jsLinkCheck.badCount, 0, 'Malicious javascript: permalink must be rejected');
    assert.match(jsLinkCheck.goodPerm, /^https:\/\/github\.com\/acme-corp\/payments-core\/blob\/.*OrderController\.java#L11-L12$/);
    assert.equal(jsLinkCheck.ownerPill, '@payments-team');

    // Screenshot 3: PRs & Quality Gate + Inline PR Review Preview (Dark)
    const darkAudit = await auditContrastAndOverflow(page, 'pr-review-dark-1440');
    assert.equal(darkAudit.overflow, false, 'Dark desktop overflow');
    assert.deepEqual(darkAudit.contrastFailures, [], 'Dark desktop WCAG AA contrast failures: ' + JSON.stringify(darkAudit.contrastFailures));
    if (screenshotDir) {
      await capturePng(page, path.join(screenshotDir, 'github-pr-quality-gate-dark.png'));
    }

    // Light Theme verification & Screenshot 4
    await page.evaluate(`document.getElementById('theme-toggle').click()`);
    await waitThemeSettled(page);
    const lightAudit = await auditContrastAndOverflow(page, 'pr-review-light-1440');
    assert.equal(lightAudit.overflow, false, 'Light desktop overflow');
    assert.deepEqual(lightAudit.contrastFailures, [], 'Light desktop WCAG AA contrast failures: ' + JSON.stringify(lightAudit.contrastFailures));
    if (screenshotDir) {
      await capturePng(page, path.join(screenshotDir, 'github-pr-quality-gate-light.png'));
    }
    // Switch back to dark
    await page.evaluate(`document.getElementById('theme-toggle').click()`);
    await waitThemeSettled(page);

    // Confirm & Publish Inline PR Review (requires explicit checkbox!)
    const btnDisabledBefore = await page.evaluate(`[...document.querySelectorAll('.gh-review-preview-card button')].find(b => b.textContent.includes('Confirm & Publish Inline PR Review')).disabled`);
    assert.equal(btnDisabledBefore, true, 'Publish button must be disabled before confirmation checkbox');
    await page.evaluate(`document.getElementById('gh-review-publish-confirm').click()`);
    await page.evaluate(`[...document.querySelectorAll('.gh-review-preview-card button')].find(b => b.textContent.includes('Confirm & Publish Inline PR Review')).click()`);
    await until('Inline PR Review published', async () => page.evaluate(`document.querySelector('.gh-publish-status')?.textContent.includes('PR Review Published')`));

    // 4. Security & SARIF tab -> Confirmed upload to GitHub Code Scanning
    await page.evaluate(`document.querySelector('[data-ent-tab="sarif"]').click()`);
    await until('SARIF tab rendered', async () => page.evaluate(`Boolean(document.getElementById('gh-sarif-upload-confirm'))`));
    await page.evaluate(`document.getElementById('gh-sarif-upload-confirm').click()`);
    await page.evaluate(`[...document.querySelectorAll('.gh-ent-body button')].find(b => b.textContent.includes('Confirm & Upload SARIF')).click()`);
    await until('SARIF uploaded to Code Scanning', async () => page.evaluate(`document.querySelector('.gh-publish-status')?.textContent.includes('SARIF Uploaded')`));

    // 5. CI Workflow tab
    await page.evaluate(`document.querySelector('[data-ent-tab="ci"]').click()`);
    await until('CI Workflow YAML generated', async () => page.evaluate(`document.querySelector('.gh-yaml-pre')?.textContent.includes('Evo Architecture Quality Gate')`));

    // 6. Webhooks & /evo ChatOps tab (verify pullNumber rendered and nonexistent /evo review removed)
    await page.evaluate(`document.querySelector('[data-ent-tab="webhooks"]').click()`);
    await until('Webhook deliveries & /evo command rendered', async () => page.evaluate(`(() => {
      const tbl = document.querySelector('.gh-webhook-table')?.textContent || '';
      const ref = document.querySelector('.gh-chatops-ref')?.textContent || '';
      return tbl.includes('/evo analyze') && tbl.includes('#42') && !ref.includes('/evo review');
    })()`));

    // Screenshot 5: Webhooks & /evo Deliveries (Dark)
    if (screenshotDir) {
      await capturePng(page, path.join(screenshotDir, 'github-issue-ci-webhooks-dark.png'));
    }

    // Close drawer, go to Review -> Findings (context = analysis:main) -> Repair Studio Step 1 -> Create GitHub Issue
    await page.evaluate(`document.querySelector('.gh-ent-close').click()`);
    await page.evaluate(`(() => { const s = document.getElementById('context'); s.value = 'analysis:main'; s.dispatchEvent(new Event('change')); })()`);
    await page.evaluate(`document.getElementById('tab-policy').click()`);
    await until('Repair Studio Step 1 rendered with CODEOWNERS pill', async () => page.evaluate(`Boolean(document.querySelector('.rs-studio')) && document.querySelector('.rs-finding-head')?.textContent.includes('@payments-team')`));

    // Run Diagnosis -> verify View on GitHub permalink + CODEOWNERS next to citation
    await page.evaluate(`[...document.querySelectorAll('.rs-studio button')].find(b => b.textContent.includes('Run Evidence-Grounded Diagnosis')).click()`);
    await until('Diagnosis citation includes View on GitHub permalink', async () => page.evaluate(`Boolean(document.querySelector('.rs-citations-list a.gh-permalink-link'))`));

    // Click citation to open Captured Source modal -> verify View on GitHub & CODEOWNERS inside source viewer
    await page.evaluate(`document.querySelector('.rs-citation-btn').click()`);
    await until('Captured Source modal shows View on GitHub permalink and CODEOWNERS', async () => page.evaluate(`document.getElementById('inspector').open && Boolean(document.querySelector('.source-viewer-bar a.gh-permalink-link')) && document.querySelector('.source-viewer-bar .gh-owner-pill')?.textContent === '@payments-team'`));
    await page.evaluate(`document.getElementById('inspector').close()`);

    // Click Create GitHub Issue -> Confirm & Create -> Issue #108 (verifies backend codeowners key)
    await page.evaluate(`[...document.querySelectorAll('.rs-studio button')].find(b => b.textContent.includes('Create GitHub Issue')).click()`);
    await until('Create GitHub Issue modal opened with codeowners', async () => page.evaluate(`document.getElementById('inspector').open && Boolean(document.getElementById('gh-issue-confirm-check')) && document.querySelector('#inspector .gh-owner-pill')?.textContent === '@payments-team'`));
    await page.evaluate(`document.getElementById('gh-issue-confirm-check').click()`);
    await page.evaluate(`[...document.querySelectorAll('#inspector button')].find(b => b.textContent.includes('Confirm & Create GitHub Issue')).click()`);
    await until('GitHub Issue #108 created', async () => page.evaluate(`document.querySelector('#inspector .gh-publish-status')?.textContent.includes('Issue #108 Created')`));
    await page.evaluate(`document.getElementById('inspector').close()`);

    // 7. AI Chat [E1] Evidence shows View on GitHub permalink + CODEOWNERS
    await page.evaluate(`document.getElementById('tab-architecture-chat').click()`);
    await until('AI Chat mounted', async () => page.evaluate(`Boolean(document.querySelector('#panel-architecture-chat textarea'))`));
    await page.evaluate(`(() => { const ta = document.querySelector('#panel-architecture-chat textarea'); ta.value = 'Why is OrderController coupled?'; ta.dispatchEvent(new Event('input', {bubbles: true})); })()`);
    await page.evaluate(`document.querySelector('#panel-architecture-chat button[type="submit"]').click()`);
    await until('AI Chat [E1] shows View on GitHub permalink and CODEOWNERS', async () => page.evaluate(`Boolean(document.querySelector('.architecture-chat-evidence a.gh-permalink-link')) && document.querySelector('.architecture-chat-evidence .gh-owner-pill')?.textContent === '@payments-team'`));

    // 8. Multi-User Tenant Isolation: Switch Account to @bob-reviewer via Session PAT
    await page.evaluate(`document.querySelector('#github-user-menu summary').click()`);
    await page.evaluate(`[...document.querySelectorAll('#github-user-menu button')].find(b => b.textContent.includes('Switch Account')).click()`);
    await until('Auth modal opened for switch', async () => page.evaluate(`document.getElementById('github-auth-dialog').open`));
    await page.evaluate(`(() => { const d = document.getElementById('modal-other-options'); if (d && !d.open) d.querySelector('summary').click(); })()`);
    await until('Other sign-in options expanded for switch', async () => page.evaluate(`document.getElementById('modal-other-options')?.open === true`));
    await page.evaluate(`[...document.querySelectorAll('#github-auth-dialog .gh-auth-tab')].find(b => b.textContent.includes('Session PAT')).click()`);
    await page.evaluate(`(() => { const inp = document.getElementById('github-auth-pat-input'); inp.value = 'ghp_bob_secret_pat_12345'; inp.dispatchEvent(new Event('input', {bubbles: true})); })()`);
    await page.evaluate(`document.querySelector('.gh-pat-form button[type="submit"]').click()`);
    await until('Switched to @bob-reviewer and loaded Bob repository', async () => page.evaluate(`document.querySelector('#github-user-menu .gh-user-login')?.textContent === '@bob-reviewer' && document.getElementById('repositories')?.textContent.includes('bob-private/secret-svc')`), 10000);

    // Assert User A's private repository is COMPLETELY WIPED from DOM
    const leakInfo = await page.evaluate(`(() => {
      const hits = [];
      for (const el of document.querySelectorAll('body *')) {
        if (el.children.length === 0 && (el.textContent || '').includes('acme-corp/payments-core')) {
          hits.push({tag: el.tagName, id: el.id, cls: el.className, parentId: el.parentElement?.id || '', text: el.textContent.slice(0, 80)});
        }
      }
      return hits;
    })()`);
    assert.deepEqual(leakInfo, [], 'Tenant isolation failure: User A private repo leaked into User B session: ' + JSON.stringify(leakInfo));

    // 9. Mobile 390x844 Light Theme + Profile Dropdown + 320x844 zero horizontal overflow
    await page.evaluate(`document.getElementById('theme-toggle').click()`);
    await waitThemeSettled(page);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 2, mobile: true});
    await new Promise(r => setTimeout(r, 150));
    await page.evaluate(`document.querySelector('#github-user-menu summary').click()`);
    await until('Mobile profile menu opened', async () => page.evaluate(`document.getElementById('github-user-menu').open`));
    const mobile390 = await auditContrastAndOverflow(page, 'mobile-390-light');
    assert.equal(mobile390.overflow, false, 'Mobile 390px horizontal overflow: ' + JSON.stringify(mobile390));
    assert.deepEqual(mobile390.contrastFailures, [], 'Mobile 390px light contrast failures: ' + JSON.stringify(mobile390.contrastFailures));
    if (screenshotDir) {
      await capturePng(page, path.join(screenshotDir, 'github-mobile-profile-light.png'));
    }

    await page.evaluate(`document.getElementById('github-user-menu').open = false`);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 320, height: 844, deviceScaleFactor: 2, mobile: true});
    await new Promise(r => setTimeout(r, 120));
    const mobile320 = await auditContrastAndOverflow(page, 'mobile-320-light');
    assert.equal(mobile320.overflow, false, 'Mobile 320px horizontal overflow: ' + JSON.stringify(mobile320));

    assert.equal(consoleErrors.length, 0, 'Unexpected console errors: ' + consoleErrors.join('; '));
    assert.equal(pageErrors.length, 0, 'Unexpected page errors: ' + pageErrors.join('; '));
    assert.ok(csrfCheckedRoutes.length >= 6, `Expected >=6 CSRF-verified mutations, got ${csrfCheckedRoutes.length}`);
    assert.ok(publishedEvents.some(e => e.type === 'review-publish'), 'Expected inline PR review publish');
    assert.ok(publishedEvents.some(e => e.type === 'sarif-upload'), 'Expected SARIF upload');
    assert.ok(publishedEvents.some(e => e.type === 'issue-publish'), 'Expected GitHub Issue publish');
    console.log(JSON.stringify({
      ok: true,
      csrfCheckedMutations: csrfCheckedRoutes.length,
      publishedEvents: publishedEvents.map(e => e.type),
      screenshots: screenshotDir ? [
        'github-signin-device-dark.png',
        'github-repo-picker-dark.png',
        'github-pr-quality-gate-dark.png',
        'github-pr-quality-gate-light.png',
        'github-issue-ci-webhooks-dark.png',
        'github-mobile-profile-light.png'
      ] : []
    }));
  } finally {
    if (page) await page.close().catch(() => {});
    const closed = new Promise(resolve => server.close(resolve));
    sockets.forEach(s => s.destroy());
    server.closeIdleConnections?.();
    server.closeAllConnections?.();
    server.unref?.();
    await Promise.race([closed, new Promise(resolve => setTimeout(resolve, 1000).unref())]);
  }
}

if (require.main === module) {
  main(process.argv.slice(2)).catch(err => {
    console.error(err);
    process.exit(1);
  });
}
