'use strict';

/**
 * Streamlined Modern Multi-User Auth Canvas (Linear / Vercel / Clerk Craft),
 * Official GitHub Invertocat CTA, 2-Field Email+Password Sign-Up, Collapsed #044 Disclosures,
 * Radically Simplified 1-Click GitHub Setup, Team Admin & Single-User Mode E2E Suite:
 * 1. Full-bleed distraction-free Auth Canvas (`body.auth-gate-active`):
 *    - Hides empty sidebar, Command/Tools bar, and redundant `.page-heading`.
 *    - Centers single-focus `<= 420px` card with Evo logo, crisp heading, NO 4-card feature wall,
 *      prominent `[GitHub Logo] Continue with GitHub`, clean 2-field Email + Password form,
 *      footer mode switch, and collapsed `"Other sign-in options"` `<details>` (closed by default!).
 *    - Zero 401 console errors, zero error banner, ZERO `$env:` / `EVO_GITHUB_` strings in DOM!
 * 2. 2-Field Create Admin Account (Email + Password only; backend auto-derives `username`):
 *    - `#404` 4-segment password strength meter + 3 rule ticks + waking submit button.
 *    - Signs in as `@alice` (`ADMIN`), exits `auth-gate-active`, reveals sidebar & `#408` account menu.
 * 3. Radically Simplified `Workspace Settings → GitHub`:
 *    - Clean status row (`Not connected`), calm localhost note (`isLoopbackHost: true`),
 *      ONE primary `[GitHub Logo] Connect GitHub App (1 Click)` button with ZERO visible form fields!
 *    - Verifies 1-Click Manifest flow payload (`evo:github-manifest-submit`).
 *    - Expands collapsed `"Manual OAuth credentials & advanced"` `<details>`, fills credentials + `#013` Device Flow switch,
 *      saves live -> status pill flips to `Connected`!
 * 4. `Workspace Settings → Team` (`ADMIN`): promotes Bob & toggles `#013` Stretchy Switch Disabled.
 * 5. `Workspace Settings → Profile`: updates display name, links GitHub via Device Code (expanded inside `"Other sign-in options"`),
 *    verifies `LOCAL_PASSWORD` `'Account'` badge + `'GitHub @alice-gh'` pill, then unlinks GitHub while staying signed in.
 * 6. Sign Out -> Wrong password triggers `#403`/`#062` shake + inline error -> Correct password signs back in.
 * 7. `/?github_setup=manifest_connected` strips query param & triggers activation toast.
 * 8. Single-user local mode (`multiUserMode: false, multiUserAuthEnabled: true, authenticated: false`):
 *    - Does NOT activate `auth-gate-active`, renders sidebar + local repo + topbar `[GitHub Logo] Sign in` button!
 * 9. Emulates `prefers-reduced-motion: reduce`, Dark 1440x900, Light 1440x900, Mobile 375x812:
 *    - 0 console/page errors, 0 horizontal overflow, 0 WCAG AA `< 4.5:1` contrast failures, 4 fresh screenshots!
 */

const assert = require('node:assert/strict');
const {createServer} = require('node:http');
const {mkdir, readFile, writeFile} = require('node:fs/promises');
const path = require('node:path');
const {edge, until} = require('./m9-edge.cjs');

async function capturePng(page, filePath) {
  await page.evaluate(`document.querySelectorAll('#toast-zone .u070-x').forEach(b => b.click())`).catch(() => {});
  await new Promise(r => setTimeout(r, 90));
  const {data} = await page.send('Page.captureScreenshot', {format: 'png'});
  await writeFile(filePath, Buffer.from(data, 'base64'));
}

async function waitThemeSettled(page) {
  await until('theme reveal veil finished', async () => page.evaluate(`!document.querySelector('.u219-veil')`), 4000);
  await new Promise(r => setTimeout(r, 320));
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
      '.topbar-auth-brand .brand-name', '.gh-card-title', '.gh-card-sub', '.gh-btn-github', '.gh-admin-bootstrap-pill',
      '.gh-auth-divider', '.gh-auth-footer-switch', '.u416-tab[aria-selected="true"]', '.u403-go',
      '.u403-f label', '.u403-msg.is-on', '.u404-rules li', '.gh-link-btn', '.u044-sum', '.gh-localhost-hint',
      '.gh-signin-btn', '.gh-user-login', '.gh-role-pill', '.gh-auth-method-pill',
      '.gh-callback-code', '.gh-team-role-select', '.u013-inline-lab', '.badge', '.u408-it'
    ];
    for (const sel of selectors) {
      for (const el of document.querySelectorAll(sel)) {
        const closedDetails = el.closest('details:not([open])');
        if (closedDetails && !el.closest('summary')) continue;
        const rect = el.getBoundingClientRect();
        if (!rect.width || !rect.height || getComputedStyle(el).visibility === 'hidden' || getComputedStyle(el).opacity === '0') continue;
        const cs = getComputedStyle(el);
        const fg = parseRgb(cs.color);
        if (!fg || fg.a === 0) continue;
        const bg = effectiveBg(el);
        const cr = ratio(fg.a < 1 ? blend(fg, bg) : fg, bg);
        if (cr < 4.5) {
          failures.push({ sel, text: el.textContent.trim().slice(0, 36), ratio: Number(cr.toFixed(2)), fg: cs.color, bg });
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

  // Stateful zero-env multi-user backend mock matching frozen contract (#100 / #115 / #116)
  const users = new Map();
  let multiUserMode = true;
  let currentUserId = null;
  let currentCsrf = '';
  let devicePollCount = 0;
  let githubSetup = {
    configured: false,
    source: 'NONE',
    host: 'github.com',
    clientId: '',
    hasClientSecret: false,
    hasWebhookSecret: false,
    deviceFlowEnabled: false,
    manifestFlowSupported: true,
    isLoopbackHost: true,
    webhookReachable: false,
    appSlug: ''
  };
  const csrfVerifiedMutations = [];

  function publicUser(u) {
    if (!u) return null;
    return {
      userId: u.userId,
      login: u.login,
      displayName: u.displayName,
      name: u.displayName,
      email: u.email,
      role: u.role,
      avatarUrl: '',
      githubLinked: u.githubLinked,
      githubLogin: u.githubLogin,
      disabled: u.disabled,
      createdAtMs: u.createdAtMs,
      orgs: u.githubLinked ? ['acme-corp'] : [],
      scopes: u.githubLinked ? ['read:user', 'repo'] : [],
      authMethod: u.authMethod,
      host: githubSetup.host || 'github.com'
    };
  }

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

      const curUser = currentUserId ? users.get(currentUserId) : null;
      const isMutation = !['GET', 'HEAD'].includes(req.method);
      const csrfExempt = route === '/api/v1/auth/register'
        || route === '/api/v1/auth/login'
        || route === '/api/v1/auth/github/device/start'
        || route === '/api/v1/auth/github/device/poll'
        || route === '/api/v1/github/auth/session';
      if (isMutation && curUser && !csrfExempt) {
        const hdr = req.headers['x-evo-csrf'];
        if (hdr !== currentCsrf) {
          res.writeHead(403, {'Content-Type': 'application/json'});
          res.end(JSON.stringify({error: {code: 'CSRF_TOKEN_INVALID', message: 'Invalid CSRF token.'}}));
          return;
        }
        csrfVerifiedMutations.push(`${req.method} ${route}`);
      }

      const sendOk = (data, status = 200) => {
        res.writeHead(status, {'Content-Type': 'application/json'});
        res.end(JSON.stringify({apiVersion: '1', data}));
      };
      const sendErr = (status, code, message) => {
        res.writeHead(status, {'Content-Type': 'application/json'});
        res.end(JSON.stringify({error: {code, message}}));
      };

      const baseOrigin = `http://127.0.0.1:${server.address().port}`;

      if (route === '/api/v1') {
        return sendOk({
          analysisJobsEnabled: true, analysisCoverageEnabled: true, repositoryConnectionsEnabled: true,
          architectureChatEnabled: true, architectureInsightsEnabled: true, evolutionEnabled: true,
          githubIntegrationEnabled: true, githubEnterpriseEnabled: true,
          multiUserAuthEnabled: true,
          multiUserMode,
          oauthWebEnabled: githubSetup.configured,
          deviceFlowEnabled: githubSetup.configured && githubSetup.deviceFlowEnabled,
          sarifExportEnabled: true, webhooksEnabled: true
        });
      }
      if (route === '/api/v1/auth/config') {
        return sendOk({
          multiUserMode,
          localAccountsEnabled: true,
          hasRegisteredUsers: users.size > 0,
          oauthWebEnabled: githubSetup.configured,
          deviceFlowEnabled: githubSetup.configured && githubSetup.deviceFlowEnabled,
          githubAppEnabled: Boolean(githubSetup.appSlug),
          patEnabled: true,
          localOperatorFallbackEnabled: !multiUserMode,
          githubSetupConfigured: githubSetup.configured,
          githubSetupSource: githubSetup.source,
          canConfigureGitHubSetup: !curUser ? users.size === 0 : curUser.role === 'ADMIN',
          callbackUrl: `${baseOrigin}/api/v1/auth/github/callback`,
          manifestFlowSupported: true,
          isLoopbackHost: true,
          webhookReachable: false,
          publicBaseUrl: baseOrigin,
          host: githubSetup.host,
          scopes: ['read:user', 'repo']
        });
      }
      if (route === '/api/v1/auth/me') {
        if (!curUser) return sendOk({authenticated: false, multiUserMode, user: null, csrfToken: ''});
        return sendOk({
          authenticated: true,
          multiUserMode,
          user: publicUser(curUser),
          csrfToken: currentCsrf,
          ...(curUser.githubLinked ? {rateLimit: {remaining: 4910, limit: 5000, resetEpochSeconds: Math.floor(Date.now() / 1000) + 1800}} : {})
        });
      }
      if (route === '/api/v1/auth/register' && req.method === 'POST') {
        const email = String(input?.email || '').trim().toLowerCase();
        const password = String(input?.password || '');
        if (!email || password.length < 8) {
          return sendErr(400, 'WEAK_PASSWORD', 'Password must be at least 8 characters.');
        }
        // Mirror backend #115 optional-username auto-derivation from email prefix!
        const rawHandle = String(input?.username || '').trim()
          || email.split('@')[0].replace(/[^a-z0-9_-]+/gi, '-').replace(/^-+|-+$/g, '').toLowerCase()
          || 'user';
        const username = rawHandle.slice(0, 32);
        const displayName = String(input?.displayName || '').trim() || (username.charAt(0).toUpperCase() + username.slice(1));
        const isFirst = users.size === 0;
        const userId = 'user-' + username.toLowerCase();
        const created = {
          userId, login: username, displayName,
          email, password, role: isFirst ? 'ADMIN' : 'MEMBER',
          githubLinked: false, githubLogin: '', disabled: false,
          authMethod: 'LOCAL_PASSWORD', createdAtMs: Date.now()
        };
        users.set(userId, created);
        if (isFirst && !users.has('user-bob')) {
          users.set('user-bob', {
            userId: 'user-bob', login: 'bob', displayName: 'Bob Engineer',
            email: 'bob@acme.dev', password: 'BobPassword!99', role: 'MEMBER',
            githubLinked: true, githubLogin: 'bob-gh', disabled: false,
            authMethod: 'LOCAL_PASSWORD', createdAtMs: Date.now() - 3600_000
          });
        }
        currentUserId = userId;
        currentCsrf = 'csrf-' + userId + '-1';
        return sendOk({authenticated: true, user: publicUser(created), csrfToken: currentCsrf}, 201);
      }
      if (route === '/api/v1/auth/login' && req.method === 'POST') {
        const id = String(input?.identifier || '').trim().toLowerCase();
        const pw = String(input?.password || '');
        const found = [...users.values()].find(u => u.email.toLowerCase() === id || u.login.toLowerCase() === id);
        if (!found || found.password !== pw) {
          return sendErr(401, 'INVALID_CREDENTIALS', 'Incorrect email, username, or password.');
        }
        if (found.disabled) {
          return sendErr(403, 'ACCOUNT_DISABLED', 'This account is disabled.');
        }
        currentUserId = found.userId;
        currentCsrf = 'csrf-' + found.userId + '-2';
        return sendOk({authenticated: true, user: publicUser(found), csrfToken: currentCsrf});
      }
      if (route === '/api/v1/auth/profile' && req.method === 'POST') {
        if (!curUser) return sendErr(401, 'AUTH_REQUIRED', 'Sign in required.');
        if (input?.displayName) curUser.displayName = String(input.displayName).trim();
        if (input?.newPassword) curUser.password = String(input.newPassword);
        return sendOk({user: publicUser(curUser)});
      }
      if (route === '/api/v1/auth/github/unlink' && req.method === 'POST') {
        if (!curUser) return sendErr(401, 'AUTH_REQUIRED', 'Sign in required.');
        curUser.githubLinked = false;
        curUser.githubLogin = '';
        return sendOk({unlinked: true, user: publicUser(curUser)});
      }
      if (route === '/api/v1/auth/users' && req.method === 'GET') {
        if (!curUser || curUser.role !== 'ADMIN') return sendErr(403, 'ADMIN_REQUIRED', 'Admin required.');
        return sendOk({items: [...users.values()].map(publicUser)});
      }
      if (route.startsWith('/api/v1/auth/users/') && route.endsWith('/role') && req.method === 'POST') {
        if (!curUser || curUser.role !== 'ADMIN') return sendErr(403, 'ADMIN_REQUIRED', 'Admin required.');
        const uid = decodeURIComponent(route.slice('/api/v1/auth/users/'.length, -'/role'.length));
        const target = users.get(uid);
        if (!target) return sendErr(404, 'INVALID_REQUEST', 'User not found.');
        if (input?.role) target.role = String(input.role).toUpperCase();
        if (typeof input?.disabled === 'boolean') target.disabled = input.disabled;
        return sendOk({user: publicUser(target)});
      }
      if (route === '/api/v1/auth/github/setup' && req.method === 'GET') {
        return sendOk({
          ...githubSetup,
          callbackUrl: `${baseOrigin}/api/v1/auth/github/callback`,
          publicBaseUrl: baseOrigin,
          canConfigure: !curUser ? users.size === 0 : curUser.role === 'ADMIN'
        });
      }
      if (route === '/api/v1/auth/github/setup' && req.method === 'POST') {
        if (curUser && curUser.role !== 'ADMIN') return sendErr(403, 'ADMIN_REQUIRED', 'Admin required.');
        githubSetup = {
          ...githubSetup,
          configured: true,
          source: 'IN_APP',
          host: input?.host || 'github.com',
          clientId: String(input?.clientId || ''),
          hasClientSecret: Boolean(input?.clientSecret || githubSetup.hasClientSecret),
          hasWebhookSecret: Boolean(input?.webhookSecret || githubSetup.hasWebhookSecret),
          deviceFlowEnabled: Boolean(input?.deviceFlowEnabled)
        };
        return sendOk({...githubSetup, callbackUrl: `${baseOrigin}/api/v1/auth/github/callback`, canConfigure: true});
      }
      if (route === '/api/v1/auth/github/setup' && req.method === 'DELETE') {
        if (!curUser || curUser.role !== 'ADMIN') return sendErr(403, 'ADMIN_REQUIRED', 'Admin required.');
        githubSetup = {
          configured: false, source: 'NONE', host: 'github.com', clientId: '',
          hasClientSecret: false, hasWebhookSecret: false, deviceFlowEnabled: false,
          manifestFlowSupported: true, isLoopbackHost: true, webhookReachable: false, appSlug: ''
        };
        return sendOk({configured: false});
      }
      if (route === '/api/v1/auth/github/manifest/prepare' && req.method === 'POST') {
        const appName = String(input?.appName || 'Evo Architecture Workbench');
        const host = String(input?.host || 'github.com');
        const callbackUrl = `${baseOrigin}/api/v1/auth/github/manifest/callback`;
        return sendOk({
          actionUrl: `https://${host}/settings/apps/new?state=st-manifest-42`,
          manifestJson: JSON.stringify({
            name: appName,
            url: baseOrigin,
            redirect_url: callbackUrl,
            callback_urls: [`${baseOrigin}/api/v1/auth/github/callback`],
            hook_attributes: {url: `${baseOrigin}/api/v1/github/webhooks`, active: false},
            public: false,
            default_permissions: {contents: 'read', pull_requests: 'write', checks: 'write'}
          }),
          state: 'st-manifest-42',
          callbackUrl
        });
      }
      if (route === '/api/v1/auth/github/device/start' && req.method === 'POST') {
        devicePollCount = 0;
        return sendOk({deviceFlowId: 'df-lucian-1', userCode: 'WDJB-MJHT', verificationUri: 'https://github.com/login/device', expiresIn: 900, interval: 1});
      }
      if (route === '/api/v1/auth/github/device/poll' && req.method === 'POST') {
        devicePollCount++;
        if (devicePollCount < 2) return sendOk({status: 'PENDING', interval: 1});
        if (curUser) {
          curUser.githubLinked = true;
          curUser.githubLogin = 'alice-gh';
        }
        return sendOk({status: 'AUTHORIZED', user: publicUser(curUser), csrfToken: currentCsrf});
      }
      if (route === '/api/v1/auth/logout' || route === '/api/v1/auth/logout-all') {
        currentUserId = null;
        currentCsrf = '';
        return sendOk({loggedOut: true});
      }
      if (route === '/api/v1/github/auth/status') {
        return sendOk(curUser?.githubLinked
          ? {authenticated: true, login: curUser.githubLogin, source: 'OAUTH_DEVICE', rateLimitRemaining: 4910, rateLimitLimit: 5000}
          : {authenticated: false, source: 'NONE', rateLimitRemaining: 60, rateLimitLimit: 60});
      }
      if (route === '/api/v1/analysis/setups') {
        if (!curUser && multiUserMode) return sendErr(401, 'AUTH_REQUIRED', 'Sign in required.');
        if (!curUser && !multiUserMode) return sendOk({items: [{id: 'local-demo', repository: 'C:/demo/local-app'}]});
        return sendOk({items: []});
      }
      if (route === '/api/v1/analysis/connections') {
        if (!curUser && multiUserMode) return sendErr(401, 'AUTH_REQUIRED', 'Sign in required.');
        if (!curUser && !multiUserMode) return sendOk({items: [{setup: 'local-demo', label: 'local-app', kind: 'LOCAL'}]});
        return sendOk({items: []});
      }
      if (route === '/api/v1/analysis/jobs/history') {
        if (!curUser && multiUserMode) return sendErr(401, 'AUTH_REQUIRED', 'Sign in required.');
        return sendOk({items: []});
      }
      return sendOk({items: []});
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

    // 1. Verify Full-Bleed Distraction-Free Modern Auth Canvas (#116)
    await until('Streamlined Auth Gate rendered', async () => page.evaluate(`document.body.classList.contains('auth-gate-active') && Boolean(document.getElementById('welcome-auth-gate')) && Boolean(document.getElementById('welcome-github-signin'))`));
    const bootAudit = await page.evaluate(`(() => {
      const txt = document.body.textContent || '';
      const cardRect = document.querySelector('#welcome-auth-gate .gh-auth-card-shell')?.getBoundingClientRect();
      return {
        noticeHidden: document.getElementById('notice').hidden,
        sidebarHidden: getComputedStyle(document.getElementById('sidebar')).display === 'none',
        pageHeadingHidden: getComputedStyle(document.querySelector('.page-heading')).display === 'none',
        cmdHidden: getComputedStyle(document.getElementById('command-k-wrap')).display === 'none',
        brandShown: getComputedStyle(document.querySelector('.topbar-auth-brand')).display !== 'none',
        featureWallCount: document.querySelectorAll('#welcome-auth-gate .u228-c').length,
        cardWidth: Math.round(cardRect?.width || 0),
        ghCtaHasSvg: Boolean(document.querySelector('#welcome-github-signin svg.gh-invertocat')),
        ghCtaLabel: document.getElementById('welcome-github-signin')?.textContent?.trim() || '',
        signupInputCount: document.querySelectorAll('#welcome-auth-gate .gh-local-auth-form input').length,
        otherDiscClosed: document.getElementById('gate-other-options')?.open === false,
        hasEnvVarCopy: /\\$env:|EVO_GITHUB_/.test(txt),
        hasAdminBootstrapHint: txt.includes('First account becomes workspace Admin'),
        createBtnSleeping: document.getElementById('gate-signup-submit')?.classList.contains('is-sleeping') === true
      };
    })()`);
    assert.equal(bootAudit.noticeHidden, true, 'Boot must not show error notice');
    assert.equal(bootAudit.sidebarHidden, true, 'Empty sidebar must be hidden on Auth Gate');
    assert.equal(bootAudit.pageHeadingHidden, true, 'Redundant page heading must be hidden on Auth Gate');
    assert.equal(bootAudit.cmdHidden, true, 'Command+K bar must be hidden on Auth Gate');
    assert.equal(bootAudit.brandShown, true, 'Minimal floating brand must be visible on Auth Gate');
    assert.equal(bootAudit.featureWallCount, 0, 'Noisy 4-card feature wall must be removed');
    assert.ok(bootAudit.cardWidth >= 340 && bootAudit.cardWidth <= 430, 'Centered auth card must be ~420px, got ' + bootAudit.cardWidth);
    assert.equal(bootAudit.ghCtaHasSvg, true, 'Continue with GitHub must include official GitHub SVG logo');
    assert.equal(bootAudit.ghCtaLabel, 'Continue with GitHub');
    assert.equal(bootAudit.signupInputCount, 2, 'Streamlined Create Account must only ask for Email + Password (2 inputs)');
    assert.equal(bootAudit.otherDiscClosed, true, '"Other sign-in options" disclosure must start collapsed');
    assert.equal(bootAudit.hasEnvVarCopy, false, 'Normal users must NEVER see $env: or EVO_GITHUB_ strings in DOM');
    assert.equal(bootAudit.hasAdminBootstrapHint, true, 'First-run bootstrap should announce Admin creation');
    assert.equal(bootAudit.createBtnSleeping, true, '#404 Submit button must start sleeping until password rules pass');
    assert.equal(consoleErrors.length, 0, 'Boot console errors: ' + consoleErrors.join('; '));

    // Clicking Continue with GitHub while unconfigured & bootstrap-eligible opens clean 1-step Connect GitHub modal!
    await page.evaluate(`document.getElementById('welcome-github-signin').click()`);
    await until('1-step Connect GitHub modal opened from Continue with GitHub', async () => page.evaluate(`document.getElementById('workspace-account-dialog').open && Boolean(document.getElementById('ws-github-manifest-btn')) && !document.querySelector('#workspace-account-dialog .u416-tl')`));
    await page.evaluate(`document.getElementById('workspace-account-dialog').close()`);

    // Audit Dark 1440x900 Auth Gate contrast & overflow + Screenshot 1
    const gateDarkAudit = await auditContrastAndOverflow(page, 'auth-gate-dark-1440');
    assert.equal(gateDarkAudit.overflow, false, 'Dark Auth Gate overflow');
    assert.deepEqual(gateDarkAudit.contrastFailures, [], 'Dark Auth Gate contrast failures: ' + JSON.stringify(gateDarkAudit.contrastFailures));
    if (screenshotDir) {
      await capturePng(page, path.join(screenshotDir, 'lucian-auth-gate-dark.png'));
    }

    // Switch to Light Theme -> Audit Light 1440x900 Auth Gate + Screenshot 2
    await page.evaluate(`document.getElementById('theme-toggle').click()`);
    await waitThemeSettled(page);
    const gateLightAudit = await auditContrastAndOverflow(page, 'auth-gate-light-1440');
    assert.equal(gateLightAudit.overflow, false, 'Light Auth Gate overflow');
    assert.deepEqual(gateLightAudit.contrastFailures, [], 'Light Auth Gate contrast failures: ' + JSON.stringify(gateLightAudit.contrastFailures));
    if (screenshotDir) {
      await capturePng(page, path.join(screenshotDir, 'lucian-auth-gate-light.png'));
    }
    // Verify expanded "Other sign-in options" disclosure also meets WCAG AA >= 4.5:1 in Light theme
    await page.evaluate(`document.querySelector('#gate-other-options > summary').click()`);
    await until('Light theme Other sign-in options opened', async () => page.evaluate(`document.getElementById('gate-other-options')?.open === true`));
    await new Promise(r => setTimeout(r, 450));
    const gateLightExpandedAudit = await auditContrastAndOverflow(page, 'auth-gate-light-expanded-1440');
    assert.equal(gateLightExpandedAudit.overflow, false, 'Light Auth Gate expanded overflow');
    assert.deepEqual(gateLightExpandedAudit.contrastFailures, [], 'Light Auth Gate expanded contrast: ' + JSON.stringify(gateLightExpandedAudit.contrastFailures));
    await page.evaluate(`document.querySelector('#gate-other-options > summary').click()`);
    // Switch back to Dark
    await page.evaluate(`document.getElementById('theme-toggle').click()`);
    await waitThemeSettled(page);

    // Test Mobile 375x812 Dark Auth Gate + Screenshot 4 + 390x844 & 320x844 overflow checks
    await page.send('Emulation.setDeviceMetricsOverride', {width: 375, height: 812, deviceScaleFactor: 2, mobile: true});
    await new Promise(r => setTimeout(r, 150));
    const gateMobileAudit = await auditContrastAndOverflow(page, 'auth-gate-mobile-375-dark');
    assert.equal(gateMobileAudit.overflow, false, 'Mobile 375px Auth Gate overflow: ' + JSON.stringify(gateMobileAudit));
    assert.deepEqual(gateMobileAudit.contrastFailures, [], 'Mobile 375px Auth Gate contrast: ' + JSON.stringify(gateMobileAudit.contrastFailures));
    if (screenshotDir) {
      await capturePng(page, path.join(screenshotDir, 'lucian-auth-mobile-375-dark.png'));
    }
    for (const [w, h] of [[390, 844], [320, 844]]) {
      await page.send('Emulation.setDeviceMetricsOverride', {width: w, height: h, deviceScaleFactor: 2, mobile: true});
      await new Promise(r => setTimeout(r, 100));
      const mob = await auditContrastAndOverflow(page, `auth-gate-mobile-${w}-dark`);
      assert.equal(mob.overflow, false, `Mobile ${w}px Auth Gate overflow: ` + JSON.stringify(mob));
    }
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await new Promise(r => setTimeout(r, 120));

    // Test clean footer toggle ("Sign in" <-> "Create an account")
    await page.evaluate(`document.querySelector('#welcome-auth-gate [data-auth-tab="signin"]').click()`);
    await until('Footer toggle switched to Sign in', async () => page.evaluate(`Boolean(document.getElementById('gate-signin-identifier'))`));
    await page.evaluate(`document.querySelector('#welcome-auth-gate [data-auth-tab="signup"]').click()`);
    await until('Footer toggle switched back to Create account', async () => page.evaluate(`Boolean(document.getElementById('gate-signup-email'))`));

    // 2. Create First Admin Account with ONLY Email + Password (#404 strength ticks + waking button)
    await page.evaluate(`(() => {
      const setVal = (id, v) => { const i = document.getElementById(id); i.value = v; i.dispatchEvent(new Event('input', {bubbles: true})); };
      setVal('gate-signup-email', 'alice@acme.dev');
      setVal('gate-signup-password', 'StrongPass#2026');
    })()`);
    const pwAudit = await page.evaluate(`({
      ticks: document.querySelectorAll('#welcome-auth-gate .u404-rules li.is-ok').length,
      meterVal: document.querySelector('#welcome-auth-gate .u404-bars')?.getAttribute('aria-valuenow'),
      awake: !document.getElementById('gate-signup-submit')?.classList.contains('is-sleeping')
    })`);
    assert.equal(pwAudit.ticks, 3, 'All 3 #404 password rules must tick green');
    assert.equal(pwAudit.meterVal, '4', '#404 password strength meter should reach level 4');
    assert.equal(pwAudit.awake, true, '#404 submit button must wake up once Email + Password are valid');

    await page.evaluate(`document.getElementById('gate-signup-submit').click()`);
    await until('Alice signed in as Admin, exited auth-gate-active, and sidebar restored', async () => page.evaluate(`!document.body.classList.contains('auth-gate-active') && getComputedStyle(document.querySelector('.topbar-auth-brand')).display === 'none' && document.querySelector('#github-user-menu .gh-user-login')?.textContent === '@alice' && document.querySelector('#github-user-menu .gh-role-pill')?.textContent === 'Admin'`));

    // Verify #408 account menu keyboard navigation (ArrowDown opens & focuses first item; Escape closes)
    const menuKb = await page.evaluate(`(() => {
      const summary = document.querySelector('#github-user-menu summary');
      summary.focus();
      summary.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowDown', bubbles: true}));
      const opened = document.getElementById('github-user-menu').open;
      const focusedLabel = document.activeElement?.textContent?.trim() || '';
      document.activeElement.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
      const closedAfterEsc = !document.getElementById('github-user-menu').open;
      return {opened, focusedLabel, closedAfterEsc};
    })()`);
    assert.equal(menuKb.opened, true, '#408 ArrowDown must open account menu');
    assert.ok(menuKb.focusedLabel.includes('Profile'), '#408 ArrowDown must focus first action item');
    assert.equal(menuKb.closedAfterEsc, true, '#408 Escape must close account menu');

    // 3. Open Workspace Settings -> Radically Simplified GitHub Setup tab
    await page.evaluate(`document.querySelector('#github-user-menu summary').click()`);
    await until('Account menu opened', async () => page.evaluate(`document.getElementById('github-user-menu').open`));
    await page.evaluate(`[...document.querySelectorAll('#github-user-menu button')].find(b => b.textContent.includes('GitHub Setup')).click()`);
    await until('Simplified GitHub Setup tab loaded', async () => page.evaluate(`document.getElementById('workspace-account-dialog').open && Boolean(document.getElementById('ws-github-manifest-btn')) && Boolean(document.getElementById('ws-github-manual-details'))`));

    const setupMinimalCheck = await page.evaluate(`({
      statusText: document.getElementById('ws-github-setup-status')?.textContent || '',
      hasLocalhostHint: (document.querySelector('.gh-localhost-hint')?.textContent || '').includes('Running on localhost'),
      manualCollapsed: document.getElementById('ws-github-manual-details')?.open === false,
      heroBtnHasSvg: Boolean(document.querySelector('#ws-github-manifest-btn svg')),
      visibleInputsBeforeExpand: [...document.querySelectorAll('#workspace-account-dialog input')].filter(i => i.getBoundingClientRect().height > 0).length
    })`);
    assert.ok(setupMinimalCheck.statusText.includes('Not connected'), 'Initial GitHub setup status must say Not connected');
    assert.equal(setupMinimalCheck.hasLocalhostHint, true, 'Loopback host must show calm localhost hint');
    assert.equal(setupMinimalCheck.manualCollapsed, true, 'Manual OAuth credentials disclosure must be CLOSED by default');
    assert.equal(setupMinimalCheck.heroBtnHasSvg, true, '1-Click GitHub App button must show official GitHub SVG');
    assert.equal(setupMinimalCheck.visibleInputsBeforeExpand, 0, 'Zero manual form inputs should be visible before expanding details');

    // Screenshot 3: Radically Simplified 1-Click GitHub Setup Tab (Dark)
    const setupDarkAudit = await auditContrastAndOverflow(page, 'github-setup-dark-1440');
    assert.equal(setupDarkAudit.overflow, false, 'GitHub Setup Dark overflow');
    assert.deepEqual(setupDarkAudit.contrastFailures, [], 'GitHub Setup Dark contrast: ' + JSON.stringify(setupDarkAudit.contrastFailures));
    if (screenshotDir) {
      await capturePng(page, path.join(screenshotDir, 'lucian-github-setup-dark.png'));
    }

    // Verify 1-Click GitHub App Manifest Flow submits directly with smart defaults
    const manifestPayload = await page.evaluate(`new Promise(resolve => {
      document.addEventListener('evo:github-manifest-submit', ev => {
        ev.preventDefault();
        resolve({
          actionUrl: ev.detail.actionUrl,
          manifest: JSON.parse(ev.detail.manifestJson),
          formMethod: ev.detail.form.method,
          hiddenName: ev.detail.form.querySelector('input[type="hidden"]')?.name
        });
      }, {once: true});
      document.getElementById('ws-github-manifest-btn').click();
    })`);
    assert.match(manifestPayload.actionUrl, /^https:\/\/github\.com\/settings\/apps\/new\?state=/);
    assert.equal(manifestPayload.formMethod.toLowerCase(), 'post');
    assert.equal(manifestPayload.hiddenName, 'manifest');
    assert.equal(manifestPayload.manifest.name, 'Evo Architecture Workbench');

    // Expand collapsed "Manual OAuth credentials & advanced" -> Save live with #013 Stretchy Switch ON
    await page.evaluate(`document.querySelector('#ws-github-manual-details summary').click()`);
    await until('Manual OAuth details expanded', async () => page.evaluate(`document.getElementById('ws-github-manual-details')?.open === true && Boolean(document.getElementById('ws-github-callback-url'))`));
    await page.evaluate(`(() => {
      const setVal = (id, v) => { const i = document.getElementById(id); i.value = v; i.dispatchEvent(new Event('input', {bubbles: true})); };
      setVal('ws-oauth-client-id', 'Iv1.liveclientid999');
      setVal('ws-oauth-client-secret', 'ghs_live_secret_value');
      document.getElementById('ws-oauth-device-switch').click();
    })()`);
    assert.equal(await page.evaluate(`document.getElementById('ws-oauth-device-switch').getAttribute('aria-checked')`), 'true');
    await page.evaluate(`document.getElementById('ws-oauth-save-btn').click()`);
    await until('GitHub status flipped live to Connected', async () => page.evaluate(`document.getElementById('ws-github-setup-status')?.textContent.includes('Connected')`));

    // 4. Switch to Team tab (ADMIN) -> Promote Bob & Toggle Disabled (#013 switch)
    await page.evaluate(`[...document.querySelectorAll('#workspace-account-dialog .u416-tab')].find(b => b.dataset.tabId === 'team').click()`);
    await until('Team Members loaded Alice & Bob', async () => page.evaluate(`document.querySelectorAll('.gh-team-row').length === 2`));
    await page.evaluate(`(() => {
      const bobRow = document.querySelector('.gh-team-row[data-user-id="user-bob"]');
      const sel = bobRow.querySelector('select');
      sel.value = 'ADMIN';
      sel.dispatchEvent(new Event('change', {bubbles: true}));
    })()`);
    await new Promise(r => setTimeout(r, 150));
    await page.evaluate(`document.querySelector('.gh-team-row[data-user-id="user-bob"] .u013-sw').click()`);
    await until('Bob switched to Disabled', async () => page.evaluate(`document.querySelector('.gh-team-row[data-user-id="user-bob"] .u013-sw')?.getAttribute('aria-checked') === 'false'`));

    // Light theme contrast check on Team Members
    await page.evaluate(`document.getElementById('workspace-account-dialog').close(); document.getElementById('theme-toggle').click();`);
    await waitThemeSettled(page);
    await page.evaluate(`window.EvoGitHubAuth.openWorkspaceAccountModal('team')`);
    await until('Team Members re-opened in Light theme', async () => page.evaluate(`document.getElementById('workspace-account-dialog').open && document.querySelectorAll('.gh-team-row').length === 2`));
    const teamLightAudit = await auditContrastAndOverflow(page, 'team-members-light-1440');
    assert.equal(teamLightAudit.overflow, false, 'Team Members Light overflow');
    assert.deepEqual(teamLightAudit.contrastFailures, [], 'Team Members Light contrast: ' + JSON.stringify(teamLightAudit.contrastFailures));
    await page.evaluate(`document.getElementById('workspace-account-dialog').close(); document.getElementById('theme-toggle').click();`);
    await waitThemeSettled(page);

    // 5. Profile tab -> Update display name -> Link GitHub via Device Code -> Unlink GitHub
    await page.evaluate(`window.EvoGitHubAuth.openWorkspaceAccountModal('profile')`);
    await until('Profile tab opened', async () => page.evaluate(`Boolean(document.getElementById('ws-profile-display-name'))`));
    await page.evaluate(`(() => {
      const i = document.getElementById('ws-profile-display-name');
      i.value = 'Alice Principal Architect';
      i.dispatchEvent(new Event('input', {bubbles: true}));
      document.getElementById('ws-profile-save-btn').click();
    })()`);
    await until('Updated display name reflected in profile card', async () => page.evaluate(`document.querySelector('.gh-profile-head-card')?.textContent.includes('Alice Principal Architect')`));

    // Link GitHub via Device Code (automatically opens "Other sign-in options" in modal!)
    await page.evaluate(`[...document.querySelectorAll('.gh-gh-link-card button')].find(b => b.textContent.includes('Link via Device Code')).click()`);
    await until('Device Code opened automatically inside modal disclosure', async () => page.evaluate(`document.getElementById('github-auth-dialog').open && document.getElementById('modal-other-options')?.open === true && Boolean(document.getElementById('github-device-start-btn'))`));
    await page.evaluate(`document.getElementById('github-device-start-btn').click()`);
    await until('Device flow authorized and closed dialog', async () => page.evaluate(`!document.getElementById('github-auth-dialog').open && window.EvoGitHubAuth.getUser()?.githubLinked === true`), 10000);

    // Verify LOCAL_PASSWORD enum renders 'Account' badge + 'GitHub @alice-gh' pill in #408 dropdown
    const linkedPillCheck = await page.evaluate(`(() => {
      const m = document.getElementById('github-user-menu');
      return {
        methodBadge: m?.querySelector('.gh-auth-method-pill')?.textContent || '',
        hasGhLinkedPill: Boolean([...(m?.querySelectorAll('.gh-role-pill.is-gh') || [])].find(n => n.textContent.includes('@alice-gh')))
      };
    })()`);
    assert.equal(linkedPillCheck.methodBadge, 'Account');
    assert.equal(linkedPillCheck.hasGhLinkedPill, true);

    await page.evaluate(`window.EvoGitHubAuth.openWorkspaceAccountModal('profile')`);
    await until('GitHub Linked badge and Unlink button shown', async () => page.evaluate(`Boolean(document.getElementById('ws-github-unlink-btn')) && document.querySelector('.gh-gh-link-card')?.textContent.includes('@alice-gh')`));
    await page.evaluate(`document.getElementById('ws-github-unlink-btn').click()`);
    await until('GitHub unlinked while Alice remains authenticated', async () => page.evaluate(`!document.getElementById('ws-github-unlink-btn') && window.EvoGitHubAuth.isAuthenticated() && window.EvoGitHubAuth.getUser()?.githubLinked === false`));
    await page.evaluate(`document.getElementById('workspace-account-dialog').close()`);

    // 6. Sign Out -> Wrong Password shakes & shows inline error -> Valid Password signs back in!
    await page.evaluate(`window.EvoGitHubAuth.signOut(false)`);
    await until('Signed out back to streamlined Auth Gate', async () => page.evaluate(`document.body.classList.contains('auth-gate-active') && Boolean(document.getElementById('gate-signin-identifier'))`));
    await page.evaluate(`(() => {
      const setVal = (id, v) => { const i = document.getElementById(id); i.value = v; i.dispatchEvent(new Event('input', {bubbles: true})); };
      setVal('gate-signin-identifier', 'alice@acme.dev');
      setVal('gate-signin-password', 'wrong-password');
      document.getElementById('gate-signin-submit').click();
    })()`);
    await until('Wrong password showed inline error & shook card', async () => page.evaluate(`document.querySelector('#welcome-auth-gate .u403-msg.is-on')?.textContent.includes('Incorrect') && document.querySelector('.gh-auth-card-shell')?.classList.contains('u062-shake')`));
    await page.evaluate(`(() => {
      const p = document.getElementById('gate-signin-password');
      p.value = 'StrongPass#2026';
      p.dispatchEvent(new Event('input', {bubbles: true}));
      document.getElementById('gate-signin-submit').click();
    })()`);
    await until('Signed back in as @alice', async () => page.evaluate(`document.querySelector('#github-user-menu .gh-user-login')?.textContent === '@alice'`));

    // 7. Reduced motion check
    await page.send('Emulation.setEmulatedMedia', {features: [{name: 'prefers-reduced-motion', value: 'reduce'}]});
    await page.evaluate(`window.EvoGitHubAuth.openWorkspaceAccountModal('profile')`);
    await until('Reduced-motion modal rendered cleanly', async () => page.evaluate(`document.getElementById('workspace-account-dialog').open`));
    assert.equal((await auditContrastAndOverflow(page, 'reduced-motion-profile')).overflow, false);

    // 8. Verify ?github_setup=manifest_connected strips query param & triggers activation toast
    await page.send('Page.navigate', {url: `http://127.0.0.1:${port}/?github_setup=manifest_connected`});
    await until('Manifest callback query stripped and toast fired', async () => page.evaluate(`!window.location.search.includes('github_setup') && (document.getElementById('toast-zone')?.textContent || '').includes('GitHub App connected')`));

    // 9. Single-user local mode: NO auth-gate-active, sidebar visible, topbar [GitHub Logo] Sign in visible!
    multiUserMode = false;
    currentUserId = null;
    currentCsrf = '';
    await page.send('Page.navigate', {url: `http://127.0.0.1:${port}/`});
    await until('Single-user local mode loaded local-app without auth gate', async () => page.evaluate(`!document.body.classList.contains('auth-gate-active') && !document.getElementById('welcome-auth-gate') && (document.getElementById('repositories')?.textContent || '').includes('local-app')`));
    const singleUserCheck = await page.evaluate(`({
      authGateActive: document.body.classList.contains('auth-gate-active'),
      sidebarShown: getComputedStyle(document.getElementById('sidebar')).display !== 'none',
      topbarSignInHasOctocat: Boolean(document.querySelector('#github-signin-trigger svg path[d*="M12 .5"]')),
      topbarSignInLabel: document.getElementById('github-signin-trigger')?.textContent?.trim() || '',
      noticeHidden: document.getElementById('notice').hidden
    })`);
    assert.deepEqual(singleUserCheck, {
      authGateActive: false,
      sidebarShown: true,
      topbarSignInHasOctocat: true,
      topbarSignInLabel: 'Sign in',
      noticeHidden: true
    }, 'Single-user local mode + topbar GitHub Sign in check');

    const storageAudit = await page.evaluate(`(() => {
      const dump = JSON.stringify({ls: {...localStorage}, ss: {...sessionStorage}});
      return {hasCsrf: dump.includes('csrf-'), hasPass: dump.includes('StrongPass'), hasSecret: dump.includes('ghs_live')};
    })()`);
    assert.deepEqual(storageAudit, {hasCsrf: false, hasPass: false, hasSecret: false}, 'Sensitive auth data leaked to storage');

    assert.equal(consoleErrors.length, 0, 'Unexpected console errors: ' + consoleErrors.join('; '));
    assert.equal(pageErrors.length, 0, 'Unexpected page errors: ' + pageErrors.join('; '));
    assert.ok(csrfVerifiedMutations.length >= 6, 'Expected >=6 CSRF-verified mutations, got: ' + JSON.stringify(csrfVerifiedMutations));

    console.log(JSON.stringify({
      ok: true,
      csrfVerifiedMutations,
      screenshots: screenshotDir ? [
        'lucian-auth-gate-dark.png',
        'lucian-auth-gate-light.png',
        'lucian-github-setup-dark.png',
        'lucian-auth-mobile-375-dark.png'
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
