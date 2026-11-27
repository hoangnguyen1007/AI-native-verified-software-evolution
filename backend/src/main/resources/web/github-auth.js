/* Streamlined Modern Multi-User Accounts, "Continue with GitHub" Auth, In-App 1-Click GitHub App Setup,
 * Team Administration, Topbar Account Menu (#408), Single-Focus Auth Card (#403/#404/#062/#044/#416),
 * Device Flow (RFC 8628), OAuth Web Redirect (PKCE), Session-Scoped RAM PAT, and Closure-Scoped CSRF.
 * Zero plaintext tokens, passwords, or CSRF tokens are ever stored in localStorage/sessionStorage/DOM. */
(() => {
  'use strict';
  const M = window.EvoModel;
  const $ = id => document.getElementById(id);
  const L = () => window.EvoLucian;
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
  const btn = (text, action, cls = 'button') => {
    const n = el('button', cls, text);
    n.type = 'button';
    n.addEventListener('click', action);
    return n;
  };

  // Strictly closure-scoped auth & CSRF memory (never persisted to localStorage/sessionStorage!)
  let csrfToken = '';
  let authConfig = null;
  let authMe = null;
  let lastUserKey = null;
  let deviceAbort = null;
  let deviceCountdownTimer = null;

  const authListeners = new Set();

  function svgIcon(paths, size = 16) {
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('width', String(size));
    svg.setAttribute('height', String(size));
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('fill', 'none');
    svg.setAttribute('stroke', 'currentColor');
    svg.setAttribute('stroke-width', '1.8');
    svg.setAttribute('stroke-linecap', 'round');
    svg.setAttribute('stroke-linejoin', 'round');
    svg.setAttribute('aria-hidden', 'true');
    for (const d of paths) {
      const p = document.createElementNS(ns, 'path');
      p.setAttribute('d', d);
      svg.append(p);
    }
    return svg;
  }

  /* Crisp official GitHub Invertocat mark (24x24 viewBox) */
  function octocatSvg(size = 20) {
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('class', 'gh-invertocat');
    svg.setAttribute('width', String(size));
    svg.setAttribute('height', String(size));
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('fill', 'currentColor');
    svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS(ns, 'path');
    path.setAttribute('fill-rule', 'evenodd');
    path.setAttribute('clip-rule', 'evenodd');
    path.setAttribute('d', 'M12 .5a12 12 0 0 0-3.79 23.39c.6.11.82-.26.82-.58v-2.04c-3.34.73-4.04-1.61-4.04-1.61-.55-1.39-1.33-1.76-1.33-1.76-1.09-.74.08-.73.08-.73 1.2.08 1.84 1.24 1.84 1.24 1.07 1.83 2.81 1.3 3.49 1 .11-.78.42-1.31.76-1.61-2.67-.3-5.47-1.34-5.47-5.93 0-1.31.47-2.38 1.24-3.22-.12-.3-.54-1.52.12-3.18 0 0 1.01-.32 3.3 1.23a11.5 11.5 0 0 1 6.01 0c2.29-1.55 3.3-1.23 3.3-1.23.66 1.66.24 2.88.12 3.18.77.84 1.24 1.91 1.24 3.22 0 4.6-2.81 5.62-5.48 5.92.43.37.81 1.1.81 2.22v3.29c0 .32.22.7.83.58A12 12 0 0 0 12 .5Z');
    svg.append(path);
    return svg;
  }

  function authMethodBadge(method) {
    const map = {
      LOCAL_PASSWORD: 'Account',
      LOCAL_ACCOUNT: 'Account',
      OAUTH_WEB: 'OAuth',
      OAUTH_DEVICE: 'Device',
      SESSION_PAT: 'PAT',
      LOCAL_CLI_OPERATOR: 'Local CLI'
    };
    return map[String(method || '').toUpperCase()] || 'Account';
  }

  function getUserMeta(user) {
    if (!user) return { isAdmin: false, isGithubLinked: false, githubLogin: '', displayName: '' };
    const role = String(user.role || '').toUpperCase();
    const method = String(user.authMethod || '').toUpperCase();
    const isGithubLinked = user.githubLinked === true
      || (user.githubLinked !== false && ['OAUTH_WEB', 'OAUTH_DEVICE', 'SESSION_PAT', 'LOCAL_CLI_OPERATOR'].includes(method));
    return {
      isAdmin: role === 'ADMIN',
      role: role || 'MEMBER',
      isGithubLinked,
      githubLogin: user.githubLogin || (isGithubLinked ? (user.login || '') : ''),
      displayName: user.displayName || user.name || ('@' + (user.login || 'user'))
    };
  }

  function effectiveConfig() {
    const caps = window.EvoApp?.state?.capabilities || {};
    return authConfig || {
      multiUserMode: Boolean(caps.multiUserMode),
      localAccountsEnabled: Boolean(caps.multiUserAuthEnabled),
      hasRegisteredUsers: true,
      oauthWebEnabled: Boolean(caps.oauthWebEnabled),
      deviceFlowEnabled: Boolean(caps.deviceFlowEnabled),
      githubAppEnabled: false,
      patEnabled: true,
      localOperatorFallbackEnabled: false,
      githubSetupConfigured: Boolean(caps.oauthWebEnabled || caps.deviceFlowEnabled),
      githubSetupSource: (caps.oauthWebEnabled || caps.deviceFlowEnabled) ? 'ENV' : 'NONE',
      canConfigureGitHubSetup: false,
      callbackUrl: window.location.origin + '/api/v1/auth/github/callback',
      manifestFlowSupported: false,
      isLoopbackHost: /^(localhost|127\.0\.0\.1|\[::1\])$/i.test(window.location.hostname),
      host: 'github.com',
      scopes: ['read:user', 'repo']
    };
  }

  function canConfigureGitHub() {
    const cfg = effectiveConfig();
    const meta = getUserMeta(authMe?.authenticated ? authMe.user : null);
    return Boolean(meta.isAdmin || cfg.canConfigureGitHubSetup);
  }

  async function rawApi(path, body, signal, method = (body === undefined ? 'GET' : 'POST')) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && method !== 'HEAD' && csrfToken) {
      headers['X-Evo-CSRF'] = csrfToken;
    }
    const resp = await fetch('/api/v1' + path, {
      method,
      credentials: 'same-origin',
      headers,
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
      signal
    });
    let payload = {};
    try {
      const text = await resp.text();
      payload = text ? JSON.parse(text) : {};
    } catch (_) {
      payload = {};
    }
    if (!resp.ok || payload.error || (resp.status >= 400 && !payload.data && (payload.code || payload.message))) {
      const errObj = payload.error && typeof payload.error === 'object' ? payload.error : payload;
      const errCode = errObj?.code || '';
      const errMsg = errObj?.message || M?.apiError?.(errCode) || `Auth request failed (${resp.status})`;
      throw Object.assign(new Error(errMsg), {
        code: errCode,
        status: resp.status
      });
    }
    return payload.data;
  }

  function computeUserKey(me) {
    if (!me?.authenticated || !me?.user) return 'anonymous';
    return String(me.user.userId || me.user.login || 'user');
  }

  function notifyListeners(changedUser) {
    for (const fn of authListeners) {
      try { fn({ me: authMe, config: authConfig, changedUser }); } catch (_) {}
    }
    document.dispatchEvent(new CustomEvent('evo:auth-changed', { detail: { me: authMe, config: authConfig, changedUser } }));
  }

  function isAuthUiEnabled() {
    const caps = window.EvoApp?.state?.capabilities || {};
    return Boolean(
      caps.multiUserAuthEnabled ||
      caps.oauthWebEnabled ||
      caps.deviceFlowEnabled ||
      authConfig?.multiUserMode ||
      authConfig?.localAccountsEnabled ||
      authConfig?.oauthWebEnabled ||
      authConfig?.deviceFlowEnabled ||
      authConfig?.patEnabled
    );
  }

  async function refreshAuth({ notifyOnChange = true } = {}) {
    const caps = window.EvoApp?.state?.capabilities || {};
    if (!caps.multiUserAuthEnabled && !caps.oauthWebEnabled && !caps.deviceFlowEnabled) {
      authConfig = null;
      authMe = null;
      csrfToken = '';
      renderTopbarAuth();
      return { me: null, config: null, changedUser: false };
    }

    const [cfg, me] = await Promise.all([
      rawApi('/auth/config', undefined, undefined, 'GET').catch(() => null),
      rawApi('/auth/me', undefined, undefined, 'GET').catch(() => null)
    ]);

    if (cfg) authConfig = cfg;
    if (me) {
      authMe = me;
      csrfToken = typeof me.csrfToken === 'string' ? me.csrfToken : '';
    } else {
      authMe = null;
      csrfToken = '';
    }

    const nextKey = computeUserKey(authMe);
    const changedUser = lastUserKey !== null && lastUserKey !== nextKey;
    lastUserKey = nextKey;

    renderTopbarAuth();
    if (notifyOnChange) notifyListeners(changedUser);
    return { me: authMe, config: authConfig, changedUser };
  }

  function buildAvatarNode(user, withOnlineDot = false) {
    const wrap = el('span', 'gh-avatar-wrap');
    const monogram = M?.sanitizeAvatarMonogram ? M.sanitizeAvatarMonogram(user?.login, user?.displayName || user?.name) : 'EV';
    const fallback = el('span', 'gh-avatar-fallback', monogram);
    wrap.append(fallback);
    if (user?.avatarUrl && M?.isSafeHttpUrl?.(user.avatarUrl)) {
      const img = el('img', 'gh-avatar-img');
      img.src = user.avatarUrl;
      img.alt = '';
      img.referrerPolicy = 'no-referrer';
      img.loading = 'lazy';
      img.addEventListener('load', () => { fallback.hidden = true; });
      img.addEventListener('error', () => { img.remove(); fallback.hidden = false; });
      wrap.append(img);
    }
    if (withOnlineDot) {
      const dot = el('span', 'u408-dot');
      dot.setAttribute('aria-hidden', 'true');
      wrap.append(dot);
    }
    return wrap;
  }

  function menuActionItem(iconPaths, label, action, isDanger = false) {
    const b = btn('', action, 'u408-it' + (isDanger ? ' is-out' : ''));
    b.append(svgIcon(iconPaths, 16), el('span', '', label));
    return b;
  }

  function renderTopbarAuth() {
    const host = $('topbar-auth-slot');
    if (!host) return;
    if (!isAuthUiEnabled()) {
      host.hidden = true;
      host.replaceChildren();
      return;
    }
    host.hidden = false;

    if (!authMe?.authenticated || !authMe?.user) {
      const signInBtn = btn('', () => openSignInModal(), 'button gh-signin-btn');
      signInBtn.id = 'github-signin-trigger';
      signInBtn.setAttribute('aria-label', 'Sign in with GitHub');
      signInBtn.append(octocatSvg(16), el('span', 'gh-signin-label', 'Sign in'));
      host.replaceChildren(signInBtn);
      return;
    }

    const user = authMe.user;
    const meta = getUserMeta(user);
    const prevMenu = $('github-user-menu');
    const wasOpenForSameUser = Boolean(prevMenu?.open && prevMenu.dataset.userLogin === String(user.login || ''));
    const menu = el('details', 'workbench-menu gh-user-menu');
    menu.id = 'github-user-menu';
    menu.dataset.userLogin = String(user.login || '');
    if (wasOpenForSameUser) menu.open = true;

    const summary = el('summary', 'gh-user-trigger');
    summary.setAttribute('aria-label', `Workspace account @${user.login}`);
    append(summary,
      buildAvatarNode(user, true),
      el('span', 'gh-user-login', '@' + (user.login || 'user')),
      meta.isAdmin ? el('span', 'gh-role-pill is-admin', 'Admin') : null,
      el('span', 'gh-auth-method-pill', authMethodBadge(user.authMethod))
    );

    const drop = el('div', 'workbench-menu-items gh-user-dropdown');

    const head = el('div', 'gh-user-drop-head');
    const isLocalMethod = user.authMethod === 'LOCAL_PASSWORD' || user.authMethod === 'LOCAL_ACCOUNT';
    const pills = append(el('div', 'gh-drop-chips'),
      el('span', 'gh-auth-method-pill', authMethodBadge(user.authMethod) + (user.host && user.host !== 'github.com' ? ' · ' + user.host : '')),
      meta.isAdmin ? el('span', 'gh-role-pill is-admin', 'Admin') : el('span', 'gh-role-pill', 'Member'),
      meta.isGithubLinked && isLocalMethod && meta.githubLogin
        ? el('span', 'gh-role-pill is-gh', 'GitHub @' + meta.githubLogin)
        : null
    );
    head.append(
      buildAvatarNode(user, false),
      append(el('div', 'gh-user-drop-id'),
        el('strong', '', meta.displayName),
        el('span', 'small muted', '@' + user.login + (user.email ? ' · ' + user.email : '')),
        pills
      )
    );
    drop.append(head);

    // Rate Limit Meter
    const rl = M?.formatRateLimit ? M.formatRateLimit(authMe.rateLimit) : { available: false };
    if (rl.available) {
      const meterWrap = el('div', 'gh-rate-meter');
      const bar = el('div', 'gh-rate-track');
      const fill = el('i', 'gh-rate-fill is-' + rl.tone);
      fill.style.width = rl.percent + '%';
      bar.append(fill);
      append(meterWrap,
        append(el('div', 'gh-rate-head'),
          el('span', 'small muted', 'GitHub API Rate Limit'),
          el('strong', 'small', rl.text)),
        bar,
        rl.resetText ? el('span', 'small muted', rl.resetText) : null
      );
      drop.append(meterWrap);
    }

    // Orgs
    if (Array.isArray(user.orgs) && user.orgs.length) {
      const orgSec = el('div', 'gh-drop-section');
      orgSec.append(el('span', 'gh-drop-label', `Organizations (${user.orgs.length})`));
      const chips = el('div', 'gh-drop-chips');
      for (const org of user.orgs.slice(0, 8)) {
        const orgLogin = typeof org === 'string' ? org : (org.login || '');
        if (!orgLogin) continue;
        chips.append(btn(orgLogin, () => {
          menu.open = false;
          window.EvoGitHubHub?.openConnectRepoBrowser?.(orgLogin);
        }, 'chip gh-org-chip'));
      }
      orgSec.append(chips);
      drop.append(orgSec);
    }

    // Scopes
    if (Array.isArray(user.scopes) && user.scopes.length) {
      const scopeSec = el('div', 'gh-drop-section');
      scopeSec.append(el('span', 'gh-drop-label', 'Active scopes'));
      const chips = el('div', 'gh-drop-chips');
      for (const sc of user.scopes.slice(0, 8)) {
        chips.append(el('span', 'gh-scope-chip', sc));
      }
      scopeSec.append(chips);
      drop.append(scopeSec);
    }

    const actions = el('div', 'gh-drop-actions');
    append(actions,
      menuActionItem(['M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2', 'M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z'], 'Profile & Security…', () => {
        menu.open = false;
        openWorkspaceAccountModal('profile');
      }),
      meta.isAdmin
        ? menuActionItem(['M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2', 'M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z', 'M23 21v-2a4 4 0 0 0-3-3.87', 'M16 3.13a4 4 0 0 1 0 7.75'], 'Team Members…', () => {
          menu.open = false;
          openWorkspaceAccountModal('team');
        })
        : null,
      canConfigureGitHub()
        ? (() => {
          const item = btn('', () => {
            menu.open = false;
            openWorkspaceAccountModal('github-setup');
          }, 'u408-it');
          item.append(octocatSvg(16), el('span', '', 'GitHub Setup…'));
          return item;
        })()
        : null,
      menuActionItem(['M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4', 'M7 10l5 5 5-5', 'M12 15V3'], 'Import GitHub Repository…', () => {
        menu.open = false;
        window.EvoGitHubHub?.openConnectRepoBrowser?.('');
      }),
      menuActionItem(['M16 3h5v5', 'M4 20L21 3', 'M21 16v5h-5', 'M15 15l6 6', 'M4 4l5 5'], 'Switch Account / Auth Method…', () => {
        menu.open = false;
        openSignInModal();
      }),
      el('div', 'u408-hr'),
      menuActionItem(['M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4', 'M16 17l5-5-5-5', 'M21 12H9'], 'Sign Out', async () => {
        menu.open = false;
        await signOut(false);
      }, true),
      menuActionItem(['M18 6L6 18', 'M6 6l12 12'], 'Sign Out All Sessions', async () => {
        menu.open = false;
        await signOut(true);
      }, true)
    );
    drop.append(actions);

    menu.append(summary, drop);
    L()?.enhanceAccountMenu?.(menu);
    host.replaceChildren(menu);
  }

  async function signOut(allSessions = false) {
    try {
      await rawApi(allSessions ? '/auth/logout-all' : '/auth/logout', {}, undefined, 'POST');
    } catch (_) {
      await rawApi('/github/auth/session', undefined, undefined, 'DELETE').catch(() => {});
    }
    csrfToken = '';
    $('workspace-account-dialog')?.close();
    await refreshAuth({ notifyOnChange: true });
    window.EvoToast?.push?.({
      kind: 'info',
      title: allSessions ? 'Signed out of all sessions' : 'Signed out'
    });
  }

  function stopDevicePolling() {
    if (deviceAbort) {
      deviceAbort.abort();
      deviceAbort = null;
    }
    if (deviceCountdownTimer) {
      clearInterval(deviceCountdownTimer);
      deviceCountdownTimer = null;
    }
  }

  /* ── Shared Lucian #403 Sign-In & #404 2-Field Create Account Form Builders ── */
  function buildLocalSignInForm({ idPrefix, cardEl, onSuccess }) {
    const form = el('form', 'gh-local-auth-form');
    form.noValidate = true;

    const idField = L().createFloatingField({
      label: 'Email or username',
      type: 'text',
      id: `${idPrefix}-signin-identifier`,
      name: 'identifier',
      autocomplete: 'username',
      required: true
    });
    const pwField = L().createFloatingField({
      label: 'Password',
      type: 'password',
      id: `${idPrefix}-signin-password`,
      name: 'password',
      autocomplete: 'current-password',
      required: true,
      withPasswordEye: true,
      withCapsWarning: true
    });
    const errMsg = el('p', 'u403-msg');
    errMsg.setAttribute('role', 'alert');

    const goBtn = L().createMorphButton({
      label: 'Sign in',
      type: 'submit',
      id: `${idPrefix}-signin-submit`
    });

    form.addEventListener('submit', async ev => {
      ev.preventDefault();
      const identifier = idField.getValue().trim();
      const password = pwField.getValue();
      errMsg.classList.remove('is-on');
      errMsg.textContent = '';

      if (!identifier || !password) {
        if (!identifier) idField.setError(true);
        if (!password) pwField.setError(true);
        errMsg.textContent = 'Enter your email or username and password.';
        errMsg.classList.add('is-on');
        L().shakeCard(cardEl || form);
        return;
      }

      goBtn.setBusy(true);
      try {
        const res = await rawApi('/auth/login', { identifier, password }, undefined, 'POST');
        pwField.setValue('');
        pwField.resetPasswordMask();
        if (res?.csrfToken) csrfToken = res.csrfToken;
        goBtn.setOk(true);
        await refreshAuth({ notifyOnChange: true });
        onSuccess?.(res);
      } catch (err) {
        goBtn.reset();
        idField.setError(true);
        pwField.setError(true);
        errMsg.textContent = err.message || 'Incorrect email, username, or password.';
        errMsg.classList.add('is-on');
        L().shakeCard(cardEl || form);
      }
    });

    append(form, idField.el, pwField.el, errMsg, goBtn.el);
    return form;
  }

  /* Minimal 2-field Create Account (Email + Password; backend auto-derives clean handle) */
  function buildLocalSignUpForm({ idPrefix, cardEl, cfg, onSuccess }) {
    const form = el('form', 'gh-local-auth-form');
    form.noValidate = true;

    const emailField = L().createFloatingField({
      label: 'Email',
      type: 'email',
      id: `${idPrefix}-signup-email`,
      name: 'email',
      autocomplete: 'email',
      required: true
    });
    const goBtn = L().createMorphButton({
      label: cfg.hasRegisteredUsers === false ? 'Create Admin account' : 'Create account',
      type: 'submit',
      id: `${idPrefix}-signup-submit`
    });
    goBtn.setAwake(false);

    const errMsg = el('p', 'u403-msg');
    errMsg.setAttribute('role', 'alert');

    function syncAwake() {
      const okEmail = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(emailField.getValue().trim());
      goBtn.setAwake(okEmail && pwStrength.isValid());
    }

    const pwStrength = L().createPasswordStrength({
      id: `${idPrefix}-signup-password`,
      label: 'Password',
      autocomplete: 'new-password',
      onChange: () => syncAwake()
    });
    emailField.input.addEventListener('input', syncAwake);

    form.addEventListener('submit', async ev => {
      ev.preventDefault();
      const email = emailField.getValue().trim();
      const password = pwStrength.getValue();

      errMsg.classList.remove('is-on');
      errMsg.textContent = '';

      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        emailField.setError(true);
        errMsg.textContent = 'Enter a valid email address.';
        errMsg.classList.add('is-on');
        L().shakeCard(cardEl || form);
        return;
      }
      if (!pwStrength.isValid()) {
        pwStrength.setError(true);
        errMsg.textContent = 'Password must have 8+ characters, a number, and a symbol.';
        errMsg.classList.add('is-on');
        L().shakeCard(cardEl || form);
        return;
      }

      goBtn.setBusy(true);
      try {
        const res = await rawApi('/auth/register', { email, password }, undefined, 'POST');
        pwStrength.field.setValue('');
        pwStrength.field.resetPasswordMask();
        if (res?.csrfToken) csrfToken = res.csrfToken;
        goBtn.setOk(true);
        await refreshAuth({ notifyOnChange: true });
        onSuccess?.(res);
      } catch (err) {
        goBtn.reset();
        syncAwake();
        errMsg.textContent = err.message || 'Could not create account.';
        errMsg.classList.add('is-on');
        L().shakeCard(cardEl || form);
      }
    });

    append(form, emailField.el, pwStrength.el, errMsg, goBtn.el);
    return form;
  }

  /* ── Shared Device Code & Session PAT Panel (inside collapsed #044 disclosure) ── */
  function buildOtherSignInDisclosure({ idPrefix, cfg, cardEl, initialOpen = false, initialAltTab = null, onSuccess }) {
    const disc = L().createDisclosure({
      id: `${idPrefix}-other-options`,
      summary: 'Other sign-in options',
      open: initialOpen,
      cls: 'gh-other-options',
      onToggle: isOpen => {
        if (!isOpen) stopDevicePolling();
        else requestAnimationFrame(() => altSeg.reposition());
      }
    });

    const altTabDefs = [
      { id: 'device', label: 'Device Code' },
      { id: 'pat', label: 'Session PAT' }
    ];
    let activeAlt = initialAltTab === 'pat' ? 'pat' : (cfg.deviceFlowEnabled ? 'device' : (initialAltTab || 'pat'));
    const altPanel = el('div', 'gh-alt-panel');
    const altSeg = L().createSegmentedTabs({
      ariaLabel: 'Alternative sign-in options',
      tabs: altTabDefs,
      activeId: activeAlt,
      extraTabClass: 'gh-auth-tab',
      onChange: id => {
        stopDevicePolling();
        activeAlt = id;
        renderAltPanel();
      }
    });

    function renderAltPanel() {
      altPanel.replaceChildren();
      if (activeAlt === 'device') {
        if (!cfg.deviceFlowEnabled) {
          altPanel.append(el('p', 'gh-alt-note', 'Device Code sign-in is not enabled yet.'));
          if (canConfigureGitHub()) {
            altPanel.append(btn('Connect GitHub in Settings →', () => {
              $('github-auth-dialog')?.close();
              openWorkspaceAccountModal('github-setup');
            }, 'button subtle'));
          }
          return;
        }

        const flowArea = el('div', 'gh-device-area');
        const statusEl = el('p', 'small muted');
        statusEl.setAttribute('role', 'status');

        const startBtn = btn('', () => void startDeviceFlow(), 'button primary gh-full-btn');
        startBtn.id = 'github-device-start-btn';
        startBtn.append(octocatSvg(16), el('span', '', 'Generate Device Code'));

        async function startDeviceFlow() {
          stopDevicePolling();
          const controller = new AbortController();
          deviceAbort = controller;
          startBtn.disabled = true;
          statusEl.textContent = 'Requesting device verification code from GitHub…';
          flowArea.replaceChildren();
          try {
            const started = await rawApi('/auth/github/device/start', { host: cfg.host || 'github.com' }, controller.signal, 'POST');
            if (controller.signal.aborted) return;

            const userCode = String(started.userCode || '');
            const verifyUri = M?.isSafeHttpUrl?.(started.verificationUri) ? started.verificationUri : 'https://github.com/login/device';
            let remainingSec = Math.max(30, Number(started.expiresIn || 900));
            let intervalSec = Math.max(1, Number(started.interval || 5));

            const codeBox = el('div', 'gh-device-code-box');
            const codeEl = el('code', 'gh-device-code', userCode);
            codeEl.id = 'github-device-user-code';
            const countdownEl = el('span', 'small muted gh-device-countdown', `Expires in ${M.formatEtaDuration(remainingSec)}`);
            const copyOpenBtn = btn('Copy Code & Open GitHub ↗', async () => {
              try {
                await navigator.clipboard?.writeText(userCode);
                window.EvoCopyFx?.flash?.(copyOpenBtn, 'Code Copied · Opening…', 'Copy Code & Open GitHub ↗');
              } catch (_) {}
              window.open(verifyUri, '_blank', 'noopener,noreferrer');
            }, 'button primary');
            const copyOnlyBtn = btn('Copy', async () => {
              try {
                await navigator.clipboard?.writeText(userCode);
                window.EvoCopyFx?.flash?.(copyOnlyBtn, 'Copied!', 'Copy');
              } catch (_) {}
            }, 'button subtle');
            const spinnerRow = append(el('div', 'gh-device-poll-row'),
              el('span', 'gh-spinner'),
              el('span', 'small', 'Waiting for authorization on GitHub…'));

            codeBox.append(
              el('span', 'small muted', 'Enter this one-time code on GitHub:'),
              codeEl,
              append(el('div', 'rs-action-row'), copyOpenBtn, copyOnlyBtn),
              countdownEl,
              spinnerRow
            );
            flowArea.replaceChildren(codeBox);
            statusEl.textContent = `Open ${verifyUri} and enter ${userCode}.`;

            deviceCountdownTimer = setInterval(() => {
              remainingSec = Math.max(0, remainingSec - 1);
              countdownEl.textContent = remainingSec > 0
                ? `Expires in ${M.formatEtaDuration(remainingSec)}`
                : 'Code expired — request a new code.';
              if (remainingSec <= 0) stopDevicePolling();
            }, 1000);

            while (!controller.signal.aborted && remainingSec > 0) {
              await new Promise(resolve => {
                const t = setTimeout(resolve, Math.min(intervalSec * 1000, 15000));
                controller.signal.addEventListener('abort', () => { clearTimeout(t); resolve(); }, { once: true });
              });
              if (controller.signal.aborted) return;
              const poll = await rawApi('/auth/github/device/poll', { deviceFlowId: started.deviceFlowId }, controller.signal, 'POST');
              if (controller.signal.aborted) return;
              const st = String(poll?.status || 'PENDING').toUpperCase();
              if (st === 'AUTHORIZED') {
                stopDevicePolling();
                if (poll.csrfToken) csrfToken = poll.csrfToken;
                await refreshAuth({ notifyOnChange: true });
                onSuccess?.('Signed in with GitHub');
                return;
              }
              if (st === 'SLOW_DOWN') {
                intervalSec = Number(poll.interval || intervalSec + 5);
                continue;
              }
              if (st === 'EXPIRED' || st === 'DENIED' || st === 'ERROR') {
                stopDevicePolling();
                statusEl.textContent = poll.error || (st === 'EXPIRED' ? 'Device code expired. Generate a new code.' : st === 'DENIED' ? 'Authorization was cancelled on GitHub.' : 'Device sign-in failed.');
                startBtn.disabled = false;
                return;
              }
            }
          } catch (err) {
            if (!controller.signal.aborted) {
              stopDevicePolling();
              statusEl.textContent = err.message || 'Could not complete Device Code sign-in.';
              startBtn.disabled = false;
            }
          }
        }

        altPanel.append(
          el('p', 'gh-alt-note', 'Sign in on github.com/login/device — zero callback URL required.'),
          startBtn, flowArea, statusEl
        );
        return;
      }

      // Session PAT (RAM only)
      const patForm = el('form', 'gh-pat-form');
      patForm.autocomplete = 'off';
      const patField = L().createFloatingField({
        label: 'Personal Access Token',
        type: 'password',
        id: idPrefix === 'modal' ? 'github-auth-pat-input' : `${idPrefix}-pat-input`,
        autocomplete: 'off',
        placeholder: 'ghp_… or github_pat_…',
        withPasswordEye: true
      });
      const patErr = el('p', 'u403-msg');
      patErr.setAttribute('role', 'alert');
      const submitBtn = L().createMorphButton({
        label: 'Sign in with Session PAT',
        type: 'submit'
      });

      patForm.addEventListener('submit', async ev => {
        ev.preventDefault();
        const token = patField.getValue().trim();
        patErr.classList.remove('is-on');
        if (!token) {
          patField.setError(true);
          patErr.textContent = 'Paste a GitHub personal access token first.';
          patErr.classList.add('is-on');
          L().shakeCard(cardEl);
          return;
        }
        submitBtn.setBusy(true);
        try {
          const res = await rawApi('/github/auth/session', { token }, undefined, 'POST');
          patField.setValue('');
          patField.resetPasswordMask();
          if (res?.csrfToken) csrfToken = res.csrfToken;
          submitBtn.setOk(true);
          await refreshAuth({ notifyOnChange: true });
          onSuccess?.('Signed in via Session PAT');
        } catch (err) {
          submitBtn.reset();
          patField.setError(true);
          patErr.textContent = err.message || 'Could not authenticate token.';
          patErr.classList.add('is-on');
          L().shakeCard(cardEl);
        }
      });

      patForm.append(
        el('p', 'gh-alt-note', 'Kept strictly in server RAM for this session — never written to disk or browser storage.'),
        patField.el, patErr, submitBtn.el
      );
      altPanel.append(patForm);
    }

    renderAltPanel();
    disc.body.append(altSeg.el, altPanel);

    return {
      el: disc.el,
      openTo(tabId) {
        disc.setOpen(true);
        altSeg.select(tabId, false);
        requestAnimationFrame(() => altSeg.reposition());
      }
    };
  }

  /* ── Unified Single-Focus Auth Card (Auth Gate & #github-auth-dialog) ── */
  function buildSingleFocusAuthCard({ idPrefix, cfg, inDialog = false, preferredMode = null, onSuccess }) {
    const localOn = cfg.localAccountsEnabled !== false;
    const isAltPreferred = preferredMode === 'device' || preferredMode === 'pat';
    let mode = (preferredMode === 'signin' || preferredMode === 'signup')
      ? preferredMode
      : (localOn && cfg.hasRegisteredUsers === false ? 'signup' : 'signin');

    const card = el('div', 'gh-auth-card-shell ux' + (inDialog ? ' is-dialog-embedded' : ''));
    const headSlot = el('div', 'gh-card-head');
    const formSlot = el('div', 'gh-card-form-slot');
    const footerSlot = el('div', 'gh-auth-footer-switch');

    const otherDisc = buildOtherSignInDisclosure({
      idPrefix,
      cfg,
      cardEl: card,
      initialOpen: isAltPreferred || !localOn,
      initialAltTab: isAltPreferred ? preferredMode : null,
      onSuccess
    });

    // Primary high-contrast GitHub CTA with crisp 20x20 official Invertocat logo
    let ghCta;
    if (cfg.oauthWebEnabled) {
      const returnTo = encodeURIComponent(window.location.pathname + window.location.hash);
      ghCta = el('a', 'gh-btn-github', '');
      ghCta.href = `/api/v1/auth/github/login?returnTo=${returnTo}`;
      ghCta.append(octocatSvg(20), el('span', '', 'Continue with GitHub'));
    } else {
      ghCta = btn('', () => {
        if (cfg.deviceFlowEnabled) {
          otherDisc.openTo('device');
          setTimeout(() => $('github-device-start-btn')?.focus(), 40);
        } else if (canConfigureGitHub()) {
          $('github-auth-dialog')?.close();
          openWorkspaceAccountModal('github-setup');
        } else {
          otherDisc.openTo('pat');
        }
      }, 'gh-btn-github');
      ghCta.append(octocatSvg(20), el('span', '', 'Continue with GitHub'));
    }
    if (!inDialog) ghCta.id = 'welcome-github-signin';

    function renderMode() {
      // Header (compact in dialog, branded emblem in full-bleed Auth Gate)
      if (!inDialog) {
        const emblem = el('div', 'gh-card-emblem');
        const logoImg = el('img', '');
        logoImg.src = '/logo.svg';
        logoImg.alt = '';
        emblem.append(logoImg);

        const titleText = !localOn
          ? 'Sign in to Evo'
          : mode === 'signup'
            ? 'Create your workspace account'
            : 'Sign in to Evo';
        headSlot.replaceChildren(
          emblem,
          el('h1', 'gh-card-title', titleText),
          mode === 'signup' && cfg.hasRegisteredUsers === false
            ? el('span', 'gh-admin-bootstrap-pill', 'First account becomes workspace Admin')
            : el('p', 'gh-card-sub', 'Architecture intelligence & verified evolution')
        );
      } else {
        const titleEl = $('github-auth-title');
        if (titleEl) {
          titleEl.textContent = mode === 'signup' ? 'Create your workspace account' : 'Sign in to Evo';
        }
      }

      // Minimal Email + Password form (ZERO 4-tab bar!)
      formSlot.replaceChildren();
      footerSlot.replaceChildren();
      if (localOn) {
        if (mode === 'signup') {
          formSlot.append(buildLocalSignUpForm({
            idPrefix,
            cardEl: card,
            cfg,
            onSuccess: () => onSuccess?.('Workspace account created')
          }));
          const toggle = btn('Sign in', () => { mode = 'signin'; renderMode(); }, 'gh-link-btn');
          toggle.dataset.authTab = 'signin';
          footerSlot.append(el('span', '', 'Already have an account?'), toggle);
        } else {
          formSlot.append(buildLocalSignInForm({
            idPrefix,
            cardEl: card,
            onSuccess: () => onSuccess?.('Signed in')
          }));
          const toggle = btn('Create an account', () => { mode = 'signup'; renderMode(); }, 'gh-link-btn');
          toggle.dataset.authTab = 'signup';
          footerSlot.append(el('span', '', 'New to Evo?'), toggle);
        }
      }
    }

    renderMode();

    const divider = localOn ? append(el('div', 'gh-auth-divider'), el('span', '', 'or')) : null;
    append(card,
      !inDialog ? headSlot : null,
      ghCta,
      divider,
      localOn ? formSlot : null,
      localOn ? footerSlot : null,
      otherDisc.el
    );
    return card;
  }

  /* ── Full-Bleed Distraction-Free Auth Gate inside #welcome ── */
  function clearInlineAuthGate(welcomeHost = $('welcome')) {
    document.body.classList.remove('auth-gate-active');
    if (!welcomeHost) return;
    welcomeHost.classList.remove('is-auth-gate');
    $('welcome-auth-gate')?.remove();
    for (const child of welcomeHost.children) {
      if (child.id === 'welcome-github-signin') child.hidden = true;
      else child.hidden = false;
    }
  }

  function renderInlineAuthGate(welcomeHost = $('welcome')) {
    if (!welcomeHost) return;
    const cfg = effectiveConfig();

    document.body.classList.add('auth-gate-active');
    welcomeHost.classList.add('is-auth-gate');
    for (const child of welcomeHost.children) {
      if (child.id !== 'welcome-auth-gate') child.hidden = true;
    }
    $('welcome-auth-gate')?.remove();

    const gate = el('section', 'gh-auth-gate ux');
    gate.id = 'welcome-auth-gate';
    gate.setAttribute('aria-label', 'Workspace sign-in');

    gate.append(buildSingleFocusAuthCard({
      idPrefix: 'gate',
      cfg,
      inDialog: false,
      onSuccess: title => {
        window.EvoToast?.push?.({
          kind: 'ok',
          title,
          detail: authMe?.user?.login ? '@' + authMe.user.login : ''
        });
      }
    }));

    welcomeHost.append(gate);
  }

  /* ── Sign-In / Switch Account Modal (#github-auth-dialog) ── */
  function openSignInModal(defaultTab = null) {
    const dialog = $('github-auth-dialog');
    if (!dialog) return;
    stopDevicePolling();
    renderSignInDialog(defaultTab);
    if (!dialog.open) dialog.showModal();
  }

  function renderSignInDialog(preferredTab = null) {
    const body = $('github-auth-dialog-body');
    if (!body) return;
    const cfg = effectiveConfig();

    body.replaceChildren(buildSingleFocusAuthCard({
      idPrefix: 'modal',
      cfg,
      inDialog: true,
      preferredMode: preferredTab,
      onSuccess: title => {
        $('github-auth-dialog')?.close();
        window.EvoToast?.push?.({
          kind: 'ok',
          title,
          detail: authMe?.user?.login ? '@' + authMe.user.login : ''
        });
      }
    }));
  }

  /* ── Account & Team Workspace Settings Modal (#workspace-account-dialog) ── */
  function openWorkspaceAccountModal(initialTab = 'profile') {
    const dialog = $('workspace-account-dialog');
    if (!dialog) return;
    renderWorkspaceAccountDialog(initialTab);
    if (!dialog.open) dialog.showModal();
  }

  function renderWorkspaceAccountDialog(preferredTab = 'profile') {
    const body = $('workspace-account-body');
    if (!body) return;
    const isAuthed = Boolean(authMe?.authenticated && authMe?.user);
    const user = isAuthed ? authMe.user : null;
    const meta = getUserMeta(user);
    const cfg = effectiveConfig();

    const tabs = [];
    const isMultiUser = Boolean(authMe?.multiUserMode || cfg.multiUserMode);
    if (isAuthed) tabs.push({ id: 'profile', label: 'Profile' });
    if (isAuthed) tabs.push({ id: 'tokens', label: 'API & MCP Tokens' });
    if (meta.isAdmin) tabs.push({ id: 'team', label: 'Team' });
    if (canConfigureGitHub()) tabs.push({ id: 'github-setup', label: 'GitHub' });
    if (meta.isAdmin || !isMultiUser) {
      tabs.push({ id: 'sandbox', label: 'Sandbox Runner' });
    }
    if (!tabs.length) {
      body.replaceChildren(el('p', 'small muted', 'Sign in to manage workspace settings.'));
      return;
    }

    let activeTab = tabs.some(t => t.id === preferredTab) ? preferredTab : tabs[0].id;
    const panel = el('div', 'gh-ws-panel');
    const seg = L().createSegmentedTabs({
      ariaLabel: 'Workspace settings tabs',
      tabs,
      activeId: activeTab,
      onChange: id => {
        activeTab = id;
        renderPanel();
      }
    });

    function renderPanel() {
      panel.replaceChildren();
      if (activeTab === 'profile') renderProfileTab(panel);
      else if (activeTab === 'tokens') void renderTokensTab(panel);
      else if (activeTab === 'team') void renderTeamTab(panel);
      else if (activeTab === 'github-setup') void renderGitHubSetupTab(panel);
      else if (activeTab === 'sandbox' && window.EvoSandboxSetup?.renderPanel) {
        window.EvoSandboxSetup.renderPanel(panel, {
          api: rawApi,
          compact: false,
          onUpdated: updated => {
            const caps = window.EvoApp?.state?.capabilities;
            if (caps && updated) {
              caps.isolatedRepairRunnerEnabled = Boolean(updated.configured);
              caps.repairIsolatedRunnerConfigured = Boolean(updated.configured);
              caps.repairSandboxMode = updated.configured ? updated.isolationMode : 'PASSIVE_REANALYSIS_ONLY';
            }
          }
        });
      }
    }

    async function renderTokensTab(host) {
      host.replaceChildren(L().createSkeletonCard(3));
      let listRes;
      try {
        listRes = await rawApi('/auth/tokens', undefined, undefined, 'GET');
      } catch (err) {
        host.replaceChildren(el('div', 'rs-inline-notice is-warn', err.message || 'Could not load personal access tokens.'));
        return;
      }
      const items = Array.isArray(listRes?.items) ? listRes.items : [];
      const wrap = el('div', 'gh-setup-wrap');

      const mintCard = el('section', 'gh-setup-card');
      mintCard.append(
        append(el('div', 'gh-setup-card-head'),
          el('strong', '', 'Create Personal Access Token (PAT)'),
          el('span', 'badge', 'SHA-256 Hash-Only')),
        el('p', 'small muted', 'Scoped tokens authenticate IDE MCP bridges and API clients via Authorization: Bearer without sharing your browser session cookie.')
      );

      const mintedHost = el('div', 'evo-pat-minted-host');
      const form = el('form', 'gh-local-auth-form');
      form.noValidate = true;

      const labelField = L().createFloatingField({
        label: 'Token label (e.g. Cursor MCP Bridge)',
        type: 'text',
        id: 'ws-pat-label',
        required: true
      });

      const scopeRow = el('div', 'evo-pat-scopes-row');
      scopeRow.append(el('span', 'small muted', 'Scopes:'));
      const scopeDefs = [
        ['mcp:read', 'mcp:read (IDE MCP tools & resources)', true],
        ['api:read', 'api:read (Read-only REST queries)', false],
        ['api:write', 'api:write (Mutations & admin ops)', false]
      ];
      const scopeChecks = new Map();
      for (const [scopeKey, scopeDesc, checked] of scopeDefs) {
        const cb = el('input');
        cb.type = 'checkbox';
        cb.value = scopeKey;
        cb.checked = checked;
        cb.id = 'ws-pat-scope-' + scopeKey.replace(':', '-');
        scopeChecks.set(scopeKey, cb);
        scopeRow.append(append(el('label', 'evo-pat-scope-opt'), cb, el('span', 'small', scopeDesc)));
      }

      const expSelect = el('select', 'gh-team-role-select');
      expSelect.id = 'ws-pat-expiry';
      expSelect.setAttribute('aria-label', 'Token expiration');
      for (const [days, txt] of [[30, 'Expires in 30 days'], [90, 'Expires in 90 days (recommended)'], [180, 'Expires in 180 days'], [365, 'Expires in 365 days']]) {
        const opt = el('option', '', txt);
        opt.value = String(days);
        if (days === 90) opt.selected = true;
        expSelect.append(opt);
      }

      const errEl = el('p', 'u403-msg');
      errEl.setAttribute('role', 'alert');
      const createBtn = L().createMorphButton({
        label: 'Mint scoped token',
        type: 'submit',
        id: 'ws-pat-create-btn'
      });

      form.addEventListener('submit', async ev => {
        ev.preventDefault();
        errEl.classList.remove('is-on');
        const label = labelField.getValue().trim();
        const scopes = [...scopeChecks.entries()].filter(([, cb]) => cb.checked).map(([k]) => k);
        const expiresInDays = Number(expSelect.value || 90);
        if (!label) {
          labelField.setError(true);
          errEl.textContent = 'Enter a descriptive token label first.';
          errEl.classList.add('is-on');
          L().shakeCard(mintCard);
          return;
        }
        if (!scopes.length) {
          errEl.textContent = 'Select at least one token scope.';
          errEl.classList.add('is-on');
          L().shakeCard(mintCard);
          return;
        }
        createBtn.setBusy(true);
        try {
          const created = await rawApi('/auth/tokens', { label, scopes, expiresInDays }, undefined, 'POST');
          labelField.setValue('');
          createBtn.reset();
          const plaintext = String(created?.token || '');
          const banner = el('div', 'evo-pat-reveal-banner');
          banner.id = 'ws-pat-minted-banner';
          const copyBtn = btn('Copy token', async () => {
            try {
              await navigator.clipboard?.writeText(plaintext);
              window.EvoCopyFx?.flash?.(copyBtn, 'Copied!', 'Copy token');
            } catch (_) {}
          }, 'button primary');
          banner.append(
            el('strong', '', 'Token minted — copy now (shown once only)'),
            append(el('div', 'evo-pat-reveal-row'), el('code', 'evo-pat-secret-code', plaintext), copyBtn),
            el('p', 'small muted', 'Export as EVO_MCP_TOKEN in your IDE MCP config. Only the SHA-256 hash is stored on the server.')
          );
          mintedHost.replaceChildren(banner);
          window.EvoToast?.push?.({ kind: 'ok', title: 'Personal access token minted', detail: label });
          await refreshTokenList(listCard);
        } catch (err) {
          createBtn.reset();
          errEl.textContent = err.message || 'Could not mint personal access token.';
          errEl.classList.add('is-on');
          L().shakeCard(mintCard);
        }
      });

      append(form, labelField.el, scopeRow, append(el('div', 'rs-action-row'), expSelect, createBtn.el), errEl);
      mintCard.append(mintedHost, form);

      const listCard = el('section', 'gh-setup-card');
      const fmtDate = ms => (ms && Number(ms) > 0) ? new Date(Number(ms)).toLocaleDateString() : 'Never';

      function populateListCard(target, tokenItems) {
        target.replaceChildren(
          append(el('div', 'gh-setup-card-head'),
            el('strong', '', `Your Access Tokens (${tokenItems.length})`),
            el('span', 'small muted', 'Per-user isolated · revocable anytime'))
        );
        if (!tokenItems.length) {
          target.append(el('p', 'small muted', 'No personal access tokens minted yet.'));
          return;
        }
        const ul = el('ul', 'evo-advisory-list');
        ul.id = 'ws-pat-list';
        for (const t of tokenItems) {
          const isRevoked = Boolean(t.revoked);
          const isExpired = Boolean(t.expired);
          const li = el('li', 'evo-advisory-item');
          li.dataset.tokenId = String(t.tokenId || '');
          const main = el('div', 'evo-advisory-item-main');
          const headRow = el('div', 'gh-profile-name-row');
          headRow.append(
            el('strong', '', t.label || t.tokenId),
            el('code', 'small', t.prefix || 'evo_pat_…'),
            el('span', 'badge ' + (isRevoked ? 'bad' : isExpired ? 'warn' : 'good'),
              isRevoked ? 'Revoked' : isExpired ? 'Expired' : 'Active')
          );
          for (const sc of (t.scopes || [])) {
            headRow.append(el('span', 'gh-role-pill', sc));
          }
          main.append(
            headRow,
            el('span', 'small muted', `Created ${fmtDate(t.createdAtMs)} · Expires ${fmtDate(t.expiresAtMs)} · Last used ${fmtDate(t.lastUsedAtMs)}`)
          );
          li.append(main);
          if (!isRevoked) {
            const revBtn = btn('Revoke', async () => {
              revBtn.disabled = true;
              try {
                await rawApi(`/auth/tokens/${encodeURIComponent(t.tokenId)}`, undefined, undefined, 'DELETE');
                window.EvoToast?.push?.({ kind: 'info', title: 'Token revoked', detail: t.label });
                await refreshTokenList(target);
              } catch (err) {
                revBtn.disabled = false;
                window.EvoToast?.push?.({ kind: 'err', title: err.message || 'Could not revoke token' });
              }
            }, 'button danger');
            revBtn.dataset.revokeToken = String(t.tokenId || '');
            li.append(revBtn);
          }
          ul.append(li);
        }
        target.append(ul);
      }

      async function refreshTokenList(target) {
        const refreshed = await rawApi('/auth/tokens', undefined, undefined, 'GET');
        populateListCard(target, Array.isArray(refreshed?.items) ? refreshed.items : []);
      }

      populateListCard(listCard, items);
      wrap.append(mintCard, listCard);
      host.replaceChildren(wrap);
    }

    function renderProfileTab(host) {
      const u = authMe?.user;
      if (!u) return;
      const m = getUserMeta(u);

      const idCard = el('div', 'gh-profile-head-card');
      idCard.append(
        buildAvatarNode(u, true),
        append(el('div', 'gh-profile-id-meta'),
          append(el('div', 'gh-profile-name-row'),
            el('strong', '', m.displayName),
            el('span', 'gh-role-pill' + (m.isAdmin ? ' is-admin' : ''), m.isAdmin ? 'Admin' : 'Member'),
            el('span', 'gh-auth-method-pill', authMethodBadge(u.authMethod))
          ),
          el('span', 'small muted', '@' + u.login + (u.email ? ' · ' + u.email : ''))
        )
      );

      // GitHub Connection Card (Link / Unlink)
      const ghCard = el('section', 'gh-setup-card gh-gh-link-card');
      const ghHead = append(el('div', 'gh-setup-card-head'),
        append(el('div', 'gh-setup-card-title'), octocatSvg(18), el('strong', '', 'GitHub Connection')),
        el('span', 'badge ' + (m.isGithubLinked ? 'good' : 'warn'), m.isGithubLinked ? `Linked · @${m.githubLogin || u.login}` : 'Not linked')
      );
      const ghStatus = el('p', 'small muted');
      ghStatus.setAttribute('role', 'status');

      if (m.isGithubLinked) {
        ghStatus.textContent = `Connected to ${u.host || 'github.com'} as @${m.githubLogin || u.login}.`;
        const unlinkBtn = btn('Unlink GitHub', async () => {
          unlinkBtn.disabled = true;
          try {
            await rawApi('/auth/github/unlink', {}, undefined, 'POST');
            await refreshAuth({ notifyOnChange: true });
            window.EvoToast?.push?.({ kind: 'info', title: 'GitHub unlinked from workspace account' });
            renderWorkspaceAccountDialog('profile');
          } catch (err) {
            unlinkBtn.disabled = false;
            ghStatus.textContent = err.message || 'Could not unlink GitHub account.';
          }
        }, 'button danger');
        unlinkBtn.id = 'ws-github-unlink-btn';
        ghCard.append(ghHead, ghStatus, append(el('div', 'rs-action-row'), unlinkBtn));
      } else {
        ghStatus.textContent = 'Link GitHub to import private repositories and post inline PR quality gates.';
        const row = el('div', 'rs-action-row');
        if (cfg.oauthWebEnabled) {
          const returnTo = encodeURIComponent(window.location.pathname + window.location.hash);
          const link = el('a', 'gh-btn-github', '');
          link.href = `/api/v1/auth/github/login?returnTo=${returnTo}`;
          link.append(octocatSvg(18), el('span', '', 'Link with GitHub'));
          row.append(link);
        }
        if (cfg.deviceFlowEnabled) {
          row.append(btn('Link via Device Code', () => {
            $('workspace-account-dialog')?.close();
            openSignInModal('device');
          }, 'button subtle'));
        }
        if (cfg.patEnabled !== false) {
          row.append(btn('Link via Session PAT', () => {
            $('workspace-account-dialog')?.close();
            openSignInModal('pat');
          }, 'button subtle'));
        }
        if (!cfg.oauthWebEnabled && !cfg.deviceFlowEnabled && canConfigureGitHub()) {
          row.append(btn('Connect GitHub →', () => seg.select('github-setup'), 'button primary'));
        }
        ghCard.append(ghHead, ghStatus, row);
      }

      // Profile & Password Form
      const profCard = el('section', 'gh-setup-card');
      profCard.append(el('strong', '', 'Profile & Password'));
      const form = el('form', 'gh-local-auth-form');
      form.noValidate = true;

      const displayField = L().createFloatingField({
        label: 'Display name',
        type: 'text',
        id: 'ws-profile-display-name',
        value: u.displayName || u.name || '',
        autocomplete: 'name'
      });
      const curPwField = L().createFloatingField({
        label: 'Current password (only to change password)',
        type: 'password',
        id: 'ws-profile-cur-pw',
        autocomplete: 'current-password',
        withPasswordEye: true
      });
      const newPwStrength = L().createPasswordStrength({
        id: 'ws-profile-new-pw',
        label: 'New password (optional)',
        autocomplete: 'new-password'
      });
      const msg = el('p', 'u403-msg');
      msg.setAttribute('role', 'alert');
      const saveBtn = L().createMorphButton({
        label: 'Save changes',
        type: 'submit',
        id: 'ws-profile-save-btn'
      });

      form.addEventListener('submit', async ev => {
        ev.preventDefault();
        msg.classList.remove('is-on');
        const displayName = displayField.getValue().trim();
        const currentPassword = curPwField.getValue();
        const newPassword = newPwStrength.getValue();
        if (newPassword && !newPwStrength.isValid()) {
          newPwStrength.setError(true);
          msg.textContent = 'New password must have 8+ characters, a number, and a symbol.';
          msg.classList.add('is-on');
          L().shakeCard(profCard);
          return;
        }
        saveBtn.setBusy(true);
        try {
          await rawApi('/auth/profile', {
            displayName,
            ...(newPassword ? { currentPassword, newPassword } : {})
          }, undefined, 'POST');
          curPwField.setValue('');
          newPwStrength.field.setValue('');
          await refreshAuth({ notifyOnChange: false });
          saveBtn.setOk(true);
          window.EvoToast?.push?.({ kind: 'ok', title: 'Profile updated', detail: displayName || '@' + u.login });
          setTimeout(() => { saveBtn.reset(); renderWorkspaceAccountDialog('profile'); }, 450);
        } catch (err) {
          saveBtn.reset();
          msg.textContent = err.message || 'Could not update profile.';
          msg.classList.add('is-on');
          L().shakeCard(profCard);
        }
      });

      append(form, displayField.el, curPwField.el, newPwStrength.el, msg, saveBtn.el);
      profCard.append(form);
      host.append(idCard, ghCard, profCard);
    }

    async function renderTeamTab(host) {
      host.replaceChildren(L().createSkeletonCard(3));
      try {
        const res = await rawApi('/auth/users', undefined, undefined, 'GET');
        const items = Array.isArray(res?.items) ? res.items : [];
        const wrap = el('div', 'gh-team-wrap');
        const statusBanner = el('p', 'u403-msg');
        statusBanner.setAttribute('role', 'alert');

        wrap.append(
          append(el('div', 'gh-setup-card-head'),
            el('strong', '', `Members (${items.length})`),
            el('span', 'small muted', 'Manage workspace roles and access.')
          ),
          statusBanner
        );

        const list = el('div', 'gh-team-list');
        const currentId = authMe?.user?.userId;
        for (const member of items) {
          const row = el('div', 'gh-team-row');
          row.dataset.userId = String(member.userId || '');
          const isSelf = currentId && member.userId === currentId;

          const idCol = append(el('div', 'gh-team-id'),
            buildAvatarNode(member, !member.disabled),
            append(el('div', 'gh-team-copy'),
              append(el('div', 'gh-profile-name-row'),
                el('strong', '', member.displayName || ('@' + member.login)),
                isSelf ? el('span', 'gh-role-pill', 'You') : null,
                member.githubLinked
                  ? el('span', 'gh-role-pill is-gh', 'GitHub @' + (member.githubLogin || member.login))
                  : null
              ),
              el('span', 'small muted', '@' + member.login + (member.email ? ' · ' + member.email : ''))
            )
          );

          const controls = el('div', 'gh-team-controls');
          const roleSel = el('select', 'gh-team-role-select');
          roleSel.setAttribute('aria-label', `Role for @${member.login}`);
          for (const [val, txt] of [['ADMIN', 'Admin'], ['MEMBER', 'Member']]) {
            const opt = el('option', '', txt);
            opt.value = val;
            if (String(member.role || 'MEMBER').toUpperCase() === val) opt.selected = true;
            roleSel.append(opt);
          }

          let lastRole = roleSel.value;
          let lastDisabled = Boolean(member.disabled);

          const activeSwitch = L().createStretchySwitch({
            id: `ws-user-active-${String(member.userId || member.login).replace(/[^a-zA-Z0-9_-]/g, '_')}`,
            label: lastDisabled ? 'Disabled' : 'Active',
            offHint: 'Access suspended',
            onHint: 'Active member',
            checked: !lastDisabled,
            disabled: Boolean(isSelf),
            onChange: async isActive => {
              statusBanner.classList.remove('is-on');
              try {
                await rawApi(`/auth/users/${encodeURIComponent(member.userId)}/role`, {
                  role: roleSel.value,
                  disabled: !isActive
                }, undefined, 'POST');
                lastDisabled = !isActive;
                const lab = activeSwitch.el.querySelector('.u013-inline-lab');
                if (lab) lab.textContent = isActive ? 'Active' : 'Disabled';
                window.EvoToast?.push?.({
                  kind: 'ok',
                  title: isActive ? `Enabled @${member.login}` : `Disabled @${member.login}`
                });
              } catch (err) {
                activeSwitch.setChecked(!lastDisabled, false);
                statusBanner.textContent = err.message || 'Could not update member status.';
                statusBanner.classList.add('is-on');
                L().shakeCard(row);
              }
            }
          });

          roleSel.addEventListener('change', async () => {
            statusBanner.classList.remove('is-on');
            const nextRole = roleSel.value;
            try {
              await rawApi(`/auth/users/${encodeURIComponent(member.userId)}/role`, {
                role: nextRole,
                disabled: lastDisabled
              }, undefined, 'POST');
              lastRole = nextRole;
              window.EvoToast?.push?.({
                kind: 'ok',
                title: `Updated @${member.login} to ${nextRole}`
              });
            } catch (err) {
              roleSel.value = lastRole;
              statusBanner.textContent = err.message || 'Could not change role.';
              statusBanner.classList.add('is-on');
              L().shakeCard(row);
            }
          });

          controls.append(roleSel, activeSwitch.el);
          row.append(idCol, controls);
          list.append(row);
        }

        wrap.append(list);
        host.replaceChildren(wrap);
      } catch (err) {
        host.replaceChildren(el('div', 'rs-inline-notice is-warn', err.message || 'Could not load workspace members.'));
      }
    }

    /* Radically Simplified GitHub Setup: Clean Status Row + 1-Click Hero CTA + Collapsed Manual <details> */
    async function renderGitHubSetupTab(panelHost) {
      panelHost.replaceChildren(L().createSkeletonCard(3));
      let setup;
      try {
        setup = await rawApi('/auth/github/setup', undefined, undefined, 'GET');
      } catch (err) {
        panelHost.replaceChildren(el('div', 'rs-inline-notice is-warn', err.message || 'Could not load GitHub setup state.'));
        return;
      }

      const wrap = el('div', 'gh-setup-wrap');
      const src = String(setup?.source || 'NONE').toUpperCase();
      const connected = Boolean(setup?.configured);
      const statusText = connected
        ? (src === 'ENV' ? 'Connected · Env' : 'Connected')
        : 'Not connected';

      // 1. Clean Top Status Row
      const statusBar = append(el('div', 'gh-setup-status-bar'),
        append(el('div', 'gh-setup-card-title'),
          octocatSvg(20),
          el('strong', '', 'GitHub Integration'),
          setup?.appSlug ? el('span', 'gh-role-pill', '@' + setup.appSlug) : null
        ),
        el('span', 'badge ' + (connected ? 'good' : 'warn'), statusText)
      );
      statusBar.id = 'ws-github-setup-status';
      wrap.append(statusBar);

      // Calm 1-line localhost hint (when on loopback)
      const isLoopback = setup?.isLoopbackHost === true
        || (setup?.isLoopbackHost === undefined && /^(localhost|127\.0\.0\.1|\[::1\])$/i.test(window.location.hostname));
      if (isLoopback) {
        wrap.append(el('p', 'gh-localhost-hint',
          'Running on localhost — GitHub sign-in works on this device. Deploy with HTTPS to invite teammates & receive Webhooks.'));
      }

      // 2. ONE Primary Hero Action (Zero Form Fields!)
      if (setup?.manifestFlowSupported !== false) {
        const heroCard = el('section', 'gh-setup-hero-card');
        const manErr = el('p', 'u403-msg');
        manErr.setAttribute('role', 'alert');

        const manBtn = btn('', async () => {
          manErr.classList.remove('is-on');
          manBtn.disabled = true;
          try {
            const prep = await rawApi('/auth/github/manifest/prepare', {
              appName: setup?.appSlug || 'Evo Architecture Workbench',
              host: setup?.host || 'github.com',
              publicBaseUrl: setup?.publicBaseUrl || window.location.origin
            }, undefined, 'POST');
            if (!M?.isSafeHttpUrl?.(prep?.actionUrl)) {
              throw new Error('Server returned an invalid GitHub manifest URL.');
            }
            const form = document.createElement('form');
            form.method = 'POST';
            form.action = prep.actionUrl;
            form.style.display = 'none';
            const hidden = document.createElement('input');
            hidden.type = 'hidden';
            hidden.name = 'manifest';
            hidden.value = String(prep.manifestJson || '{}');
            form.append(hidden);

            const ev = new CustomEvent('evo:github-manifest-submit', {
              cancelable: true,
              detail: {
                actionUrl: prep.actionUrl,
                manifestJson: prep.manifestJson,
                state: prep.state,
                callbackUrl: prep.callbackUrl,
                form
              }
            });
            const proceed = document.dispatchEvent(ev);
            if (proceed) {
              document.body.append(form);
              form.submit();
              form.remove();
            } else {
              manBtn.disabled = false;
            }
          } catch (err) {
            manBtn.disabled = false;
            manErr.textContent = err.message || 'Could not start 1-Click GitHub App registration.';
            manErr.classList.add('is-on');
            L().shakeCard(heroCard);
          }
        }, 'gh-btn-github');
        manBtn.id = 'ws-github-manifest-btn';
        manBtn.append(
          octocatSvg(20),
          el('span', '', connected ? 'Reconnect GitHub App (1 Click)' : 'Connect GitHub App (1 Click)')
        );

        heroCard.append(
          manBtn,
          el('p', 'small muted', 'Registers a private GitHub App and activates sign-in automatically — zero key copy-paste.'),
          manErr
        );
        wrap.append(heroCard);
      }

      // 3. Collapsed #044 Disclosure: "Manual OAuth credentials & advanced" (CLOSED by default!)
      const manualDisc = L().createDisclosure({
        id: 'ws-github-manual-details',
        summary: 'Manual OAuth credentials & advanced',
        open: false
      });

      const cbUrl = setup?.callbackUrl || cfg.callbackUrl || (window.location.origin + '/api/v1/auth/github/callback');
      const cbRow = el('div', 'gh-callback-row');
      const cbCode = el('code', 'gh-callback-code', cbUrl);
      cbCode.id = 'ws-github-callback-url';
      const copyCbBtn = btn('Copy', async () => {
        try {
          await navigator.clipboard?.writeText(cbUrl);
          window.EvoCopyFx?.flash?.(copyCbBtn, 'Copied!', 'Copy');
        } catch (_) {}
      }, 'button subtle');
      cbRow.append(el('span', 'small muted', 'Authorization Callback URL'), append(el('div', 'gh-callback-box'), cbCode, copyCbBtn));

      const manualForm = el('form', 'gh-local-auth-form');
      manualForm.noValidate = true;

      const hostField = L().createFloatingField({
        label: 'GitHub host',
        type: 'text',
        id: 'ws-oauth-host',
        value: setup?.host || 'github.com'
      });
      const clientIdField = L().createFloatingField({
        label: 'OAuth Client ID',
        type: 'text',
        id: 'ws-oauth-client-id',
        value: setup?.clientId || '',
        required: true
      });
      const clientSecretField = L().createFloatingField({
        label: setup?.hasClientSecret ? 'Client Secret (saved — leave blank to keep)' : 'OAuth Client Secret',
        type: 'password',
        id: 'ws-oauth-client-secret',
        autocomplete: 'new-password',
        withPasswordEye: true
      });
      const webhookSecretField = L().createFloatingField({
        label: setup?.hasWebhookSecret ? 'Webhook Secret (saved — optional)' : 'Webhook Secret (optional)',
        type: 'password',
        id: 'ws-oauth-webhook-secret',
        autocomplete: 'new-password',
        withPasswordEye: true
      });
      const deviceSwitch = L().createStretchySwitch({
        id: 'ws-oauth-device-switch',
        label: 'Enable Device Code Flow',
        offHint: 'Browser redirect only',
        onHint: 'github.com/login/device enabled',
        checked: Boolean(setup?.deviceFlowEnabled)
      });

      const manualErr = el('p', 'u403-msg');
      manualErr.setAttribute('role', 'alert');
      const saveBtn = L().createMorphButton({
        label: 'Save & activate',
        type: 'submit',
        id: 'ws-oauth-save-btn'
      });

      manualForm.addEventListener('submit', async ev => {
        ev.preventDefault();
        manualErr.classList.remove('is-on');
        const clientId = clientIdField.getValue().trim();
        const clientSecret = clientSecretField.getValue().trim();
        const webhookSecret = webhookSecretField.getValue().trim();
        const ghHost = hostField.getValue().trim() || 'github.com';
        if (!clientId) {
          clientIdField.setError(true);
          manualErr.textContent = 'Enter your GitHub OAuth Client ID.';
          manualErr.classList.add('is-on');
          L().shakeCard(manualDisc.el);
          return;
        }
        saveBtn.setBusy(true);
        try {
          await rawApi('/auth/github/setup', {
            host: ghHost,
            clientId,
            ...(clientSecret ? { clientSecret } : {}),
            ...(webhookSecret ? { webhookSecret } : {}),
            deviceFlowEnabled: deviceSwitch.getChecked()
          }, undefined, 'POST');
          clientSecretField.setValue('');
          webhookSecretField.setValue('');
          await refreshAuth({ notifyOnChange: false });
          saveBtn.setOk(true);
          window.EvoToast?.push?.({ kind: 'ok', title: 'GitHub OAuth saved & activated live' });
          setTimeout(() => { saveBtn.reset(); void renderGitHubSetupTab(panelHost); }, 350);
        } catch (err) {
          saveBtn.reset();
          manualErr.textContent = err.message || 'Could not save GitHub OAuth configuration.';
          manualErr.classList.add('is-on');
          L().shakeCard(manualDisc.el);
        }
      });

      append(manualForm,
        append(el('div', 'gh-two-col'), hostField.el, clientIdField.el),
        clientSecretField.el,
        webhookSecretField.el,
        deviceSwitch.el,
        manualErr,
        saveBtn.el
      );

      manualDisc.body.append(cbRow, manualForm);

      if (src === 'IN_APP' && meta.isAdmin) {
        const clearBtn = btn('Disconnect In-App Config', async () => {
          clearBtn.disabled = true;
          try {
            await rawApi('/auth/github/setup', undefined, undefined, 'DELETE');
            await refreshAuth({ notifyOnChange: false });
            window.EvoToast?.push?.({ kind: 'info', title: 'Cleared in-app GitHub OAuth configuration' });
            void renderGitHubSetupTab(panelHost);
          } catch (err) {
            clearBtn.disabled = false;
            manualErr.textContent = err.message || 'Could not clear GitHub OAuth configuration.';
            manualErr.classList.add('is-on');
          }
        }, 'button danger');
        clearBtn.id = 'ws-oauth-clear-btn';
        manualDisc.body.append(append(el('div', 'rs-action-row'), clearBtn));
      }

      wrap.append(manualDisc.el);
      panelHost.replaceChildren(wrap);
    }

    const wsTitle = $('workspace-account-title');
    if (wsTitle) {
      wsTitle.textContent = (tabs.length === 1 && tabs[0].id === 'github-setup')
        ? 'Connect GitHub'
        : 'Workspace Settings';
    }
    renderPanel();
    if (tabs.length > 1) {
      body.replaceChildren(seg.el, panel);
      requestAnimationFrame(() => seg.reposition());
    } else {
      seg.destroy();
      body.replaceChildren(panel);
    }
  }

  function handleOAuthReturnFlag() {
    try {
      const sp = new URLSearchParams(window.location.search);
      const authStatus = sp.get('auth');
      const authError = sp.get('auth_error');
      const githubSetupFlag = sp.get('github_setup');
      if (!authStatus && !authError && !githubSetupFlag) return;
      sp.delete('auth');
      sp.delete('auth_error');
      sp.delete('github_setup');
      const qs = sp.toString();
      const cleanUrl = window.location.pathname + (qs ? '?' + qs : '') + window.location.hash;
      window.history.replaceState({}, '', cleanUrl);
      if (authError) {
        window.EvoToast?.push?.({ kind: 'err', title: 'GitHub sign-in failed', detail: authError });
      } else if (githubSetupFlag === 'manifest_connected' || authStatus === 'github_app_configured') {
        setTimeout(() => {
          window.EvoToast?.push?.({ kind: 'ok', title: 'GitHub App connected & activated live' });
        }, 120);
      } else if (authStatus === 'github_linked') {
        setTimeout(() => {
          window.EvoToast?.push?.({ kind: 'ok', title: 'GitHub account linked to your workspace profile' });
        }, 120);
      } else if (authStatus === 'signed_in' || authStatus === 'ok') {
        setTimeout(() => {
          const login = authMe?.user?.login;
          window.EvoToast?.push?.({ kind: 'ok', title: login ? `Signed in as @${login}` : 'Signed in with GitHub' });
        }, 120);
      }
    } catch (_) {}
  }

  function init() {
    const dialog = $('github-auth-dialog');
    dialog?.addEventListener('close', () => {
      stopDevicePolling();
      const pat = $('github-auth-pat-input');
      if (pat) pat.value = '';
      dialog.querySelectorAll('.evo-pat-minted-host').forEach(host => host.replaceChildren());
    });
    handleOAuthReturnFlag();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  window.EvoGitHubAuth = {
    refreshAuth,
    openSignInModal,
    openWorkspaceAccountModal,
    renderInlineAuthGate,
    clearInlineAuthGate,
    signOut,
    getCsrfToken: () => csrfToken,
    getUser: () => authMe?.authenticated ? authMe.user : null,
    getUserId: () => authMe?.authenticated && authMe?.user ? String(authMe.user.userId || authMe.user.login || 'user') : 'local',
    getState: () => ({ me: authMe, config: authConfig }),
    isAuthenticated: () => Boolean(authMe?.authenticated && authMe?.user),
    onChange: fn => { authListeners.add(fn); return () => authListeners.delete(fn); }
  };
})();
