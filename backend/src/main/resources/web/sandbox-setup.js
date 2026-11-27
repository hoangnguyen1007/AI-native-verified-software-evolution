/* In-App 1-Click Isolated Sandbox Setup & Environment Probe Panel (Moonshot #1).
 * Hot-reloads CapturedRepairRunner without terminal flags while staying strictly fail-closed
 * (--network=none --read-only --cap-drop=ALL --security-opt=no-new-privileges:true, zero host execution).
 * Strict zero-XSS DOM construction. */
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
  const btn = (text, action, cls = 'button') => {
    const n = el('button', cls, text);
    n.type = 'button';
    n.addEventListener('click', action);
    return n;
  };

  function statusTone(setup) {
    if (!setup || !setup.configured) return 'warn';
    const mode = String(setup.isolationMode || '').toUpperCase();
    return (mode === 'QEMU_IN_DOCKER' || mode === 'QEMU_GUEST_VM' || mode === 'DOCKER_HARDENED_CONTAINER') ? 'good' : 'warn';
  }

  function statusLabel(setup) {
    if (!setup || !setup.configured) return 'Passive Reanalysis Only';
    const mode = String(setup.isolationMode || '').toUpperCase();
    if (mode === 'QEMU_IN_DOCKER' || mode === 'QEMU_GUEST_VM') return 'Isolated QEMU VM Active';
    if (mode === 'DOCKER_HARDENED_CONTAINER') return 'Hardened Container Active';
    return 'Isolated Runner Active';
  }

  function renderStatusBadge(setup) {
    const tone = statusTone(setup);
    const pill = el('span', 'badge ' + tone + ' rs-sandbox-status-pill', statusLabel(setup));
    const bt = setup?.config?.buildTool || setup?.buildTool;
    if (bt) {
      pill.title = `Isolation: ${setup.isolationMode || 'PASSIVE'} · Build tool: ${bt}`;
    }
    return pill;
  }

  function renderProbeResults(probeHost, probe) {
    probeHost.replaceChildren();
    if (!probe) return;
    const card = el('div', 'rs-probe-card');
    const summaryRow = el('div', 'rs-verdict-badges');
    append(summaryRow,
      el('span', 'badge ' + (probe.dockerCliAvailable ? 'good' : 'warn'),
        probe.dockerCliAvailable ? 'Docker CLI ✓' : 'Docker CLI Missing'),
      el('span', 'badge ' + (probe.dockerDaemonReachable ? 'good' : 'warn'),
        probe.dockerDaemonReachable ? 'Docker Daemon Reachable ✓' : 'Docker Daemon Offline'),
      el('span', 'badge ' + (probe.pythonAvailable ? 'good' : 'warn'),
        probe.pythonAvailable ? `Python ✓ (${probe.pythonExecutable || 'python3'})` : 'Python Missing'),
      probe.dockerContext ? el('span', 'badge', `Context: ${probe.dockerContext}`) : null
    );
    card.append(summaryRow);
    if (probe.summary) {
      card.append(el('p', 'small muted', probe.summary));
    }

    const toolchains = Array.isArray(probe.toolchains) ? probe.toolchains : [];
    if (toolchains.length) {
      const grid = el('div', 'rs-toolchain-grid');
      for (const tc of toolchains) {
        const statusStr = String(tc.status || (tc.ready ? 'READY' : 'OFFLINE_TOOLCHAIN_MISSING')).toUpperCase();
        const ready = statusStr === 'READY';
        const eco = tc.buildTool || tc.ecosystem || 'unknown';
        const cachePath = tc.cachePath || tc.detectedCache || tc.detectedCachePath || '';
        const tile = el('div', 'rs-toolchain-tile ' + (ready ? 'is-ready' : 'is-missing'));
        tile.append(
          append(el('div', 'rs-toolchain-head'),
            el('strong', '', String(eco).toUpperCase()),
            el('span', 'badge ' + (ready ? 'good' : 'warn'), statusStr)),
          cachePath ? el('span', 'small mono muted rs-wrap-anywhere', cachePath) : null,
          tc.detail ? el('p', 'small muted', tc.detail) : null
        );
        grid.append(tile);
      }
      card.append(grid);
    }

    const images = Array.isArray(probe.pinnedLocalImages) ? probe.pinnedLocalImages : [];
    if (images.length) {
      const imgList = el('div', 'rs-pinned-images');
      imgList.append(el('span', 'small muted', `Pinned local images (${images.length}):`));
      for (const img of images.slice(0, 8)) {
        imgList.append(el('code', 'rs-code-chip', img));
      }
      card.append(imgList);
    }

    probeHost.append(card);
  }

  function renderPanel(host, opts = {}) {
    const api = opts.api || window.EvoApp?.api;
    const signal = opts.signal;
    const onUpdated = typeof opts.onUpdated === 'function' ? opts.onUpdated : () => {};
    const compact = Boolean(opts.compact);

    if (!host || typeof api !== 'function') return { refresh: async () => {} };

    const wrap = el('section', 'rs-sandbox-panel' + (compact ? ' is-compact' : ''));
    wrap.setAttribute('aria-label', 'Isolated Sandbox Setup');
    const head = el('div', 'rs-sandbox-head');
    const body = el('div', 'rs-sandbox-body');
    const probeHost = el('div', 'rs-probe-host');
    const statusLine = el('p', 'small muted rs-sandbox-msg');
    statusLine.setAttribute('role', 'status');
    wrap.append(head, body, probeHost, statusLine);
    host.replaceChildren(wrap);

    let currentSetup = null;

    function setMsg(text, isError = false) {
      statusLine.textContent = text || '';
      statusLine.className = isError ? 'form-error rs-sandbox-msg' : 'small muted rs-sandbox-msg';
    }

    function renderState(setup) {
      currentSetup = setup;
      head.replaceChildren();
      body.replaceChildren();

      const titleGroup = append(el('div', 'rs-sandbox-title-group'),
        el('strong', '', 'Isolated Sandbox Runner (Zero Host Execution)'),
        renderStatusBadge(setup),
        el('span', 'badge', 'Fail-Closed · --network=none --read-only --cap-drop=ALL')
      );
      head.append(titleGroup);

      const cfg = setup?.config || setup || {};
      const subtitle = el('p', 'small muted',
        setup?.configured
          ? `Active runner (${setup.isolationMode}, tool: ${cfg.buildTool || 'auto'}) hot-reloaded for Repair Studio without terminal flags.`
          : 'No isolated build/test runner is configured yet. Click Probe or 1-Click Auto-Setup to detect Docker, pinned official images, and local offline dependency caches.');
      body.append(subtitle);

      // 1-Click Actions
      const actionBar = el('div', 'rs-action-row');
      const probeBtn = btn('Probe Environment', async () => {
        probeBtn.disabled = true;
        setMsg('Probing Docker CLI/daemon, Python, pinned local images, and offline caches (read-only)…');
        try {
          const res = await api('/repairs/sandbox/probe', {}, signal, 'POST', 30000);
          renderProbeResults(probeHost, res?.probe || res);
          setMsg('Environment probe completed.');
        } catch (err) {
          if (!signal?.aborted) setMsg(err.message || 'Sandbox probe failed.', true);
        } finally {
          probeBtn.disabled = false;
        }
      }, 'button subtle');

      const autoBtn = btn('1-Click Auto-Setup Sandbox', async () => {
        autoBtn.disabled = true;
        setMsg('Auto-detecting Docker & offline caches and hot-reloading CapturedRepairRunner…');
        try {
          const res = await api('/repairs/sandbox/setup', {
            autoDetect: true,
            isolationMode: modeSel.value,
            buildTool: toolSel.value
          }, signal, 'POST', 30000);
          renderState(res);
          renderProbeResults(probeHost, res?.probe || res?.lastProbe || null);
          setMsg('Isolated sandbox runner configured and hot-reloaded.');
          window.EvoToast?.push?.({ kind: 'ok', title: 'Isolated Sandbox Active', detail: `${res.isolationMode} (${res.config?.buildTool || toolSel.value})` });
          onUpdated(res);
        } catch (err) {
          if (!signal?.aborted) setMsg(err.message || '1-Click setup failed.', true);
        } finally {
          autoBtn.disabled = false;
        }
      }, 'button primary');

      const disableBtn = btn('Disable Isolated Runner', async () => {
        disableBtn.disabled = true;
        setMsg('Switching back to Passive Reanalysis Only…');
        try {
          const res = await api('/repairs/sandbox/setup', undefined, signal, 'DELETE', 15000);
          renderState(res);
          setMsg('Isolated sandbox runner disabled (Passive Reanalysis Only).');
          onUpdated(res);
        } catch (err) {
          if (!signal?.aborted) setMsg(err.message || 'Failed to disable sandbox runner.', true);
        } finally {
          disableBtn.disabled = false;
        }
      }, 'button danger');
      disableBtn.hidden = !setup?.configured;

      append(actionBar, autoBtn, probeBtn, disableBtn);

      // Manual / Advanced configuration form
      const details = el('details', 'rs-sandbox-advanced');
      details.open = !compact && Boolean(setup?.configured);
      details.append(el('summary', '', 'Configure Isolation Mode, Polyglot Toolchain & Offline Caches'));

      const form = el('div', 'rs-sandbox-grid');
      const modeSel = el('select', 'rs-select');
      modeSel.setAttribute('aria-label', 'Sandbox isolation mode');
      modeSel.append(
        new Option('Hardened Container (--network=none --read-only · Polyglot + Java)', 'DOCKER_HARDENED_CONTAINER'),
        new Option('Networkless QEMU Guest VM (Java Maven/Gradle)', 'QEMU_IN_DOCKER')
      );
      modeSel.value = setup?.isolationMode === 'QEMU_IN_DOCKER' ? 'QEMU_IN_DOCKER' : 'DOCKER_HARDENED_CONTAINER';

      const toolSel = el('select', 'rs-select');
      toolSel.setAttribute('aria-label', 'Sandbox build tool');
      for (const [val, lbl] of [
        ['auto', 'Auto-detect (Maven / Gradle / Node / Python / Go)'],
        ['maven', 'Java — Maven (-o offline)'],
        ['gradle', 'Java — Gradle (--offline)'],
        ['node', 'TypeScript / Node (tsc / vitest / jest / npm --offline)'],
        ['python', 'Python (compileall / mypy / pytest)'],
        ['go', 'Go (GOPROXY=off go test -json)']
      ]) {
        toolSel.append(new Option(lbl, val));
      }
      toolSel.value = cfg.buildTool || 'auto';

      const imgInput = el('input', 'rs-input');
      imgInput.placeholder = 'Optional pinned digest, e.g. eclipse-temurin@sha256:… or node@sha256:…';
      imgInput.value = cfg.baseImage || '';

      const m2Input = el('input', 'rs-input');
      m2Input.placeholder = 'Maven local repo (~/.m2/repository)';
      m2Input.value = cfg.m2Repository || '';

      const gradleInput = el('input', 'rs-input');
      gradleInput.placeholder = 'Gradle home (~/.gradle)';
      gradleInput.value = cfg.gradleHome || '';

      const npmInput = el('input', 'rs-input');
      npmInput.placeholder = 'npm offline cache / node_modules parent';
      npmInput.value = cfg.npmCache || '';

      const pipInput = el('input', 'rs-input');
      pipInput.placeholder = 'Python offline wheelhouse / site-packages';
      pipInput.value = cfg.pipWheelhouse || '';

      const goInput = el('input', 'rs-input');
      goInput.placeholder = 'Go module cache (GOMODCACHE)';
      goInput.value = cfg.goModCache || '';

      const timeoutInput = el('input', 'rs-input');
      timeoutInput.type = 'number';
      timeoutInput.min = '30';
      timeoutInput.max = '1800';
      timeoutInput.value = String(cfg.timeoutSeconds || 900);

      const skipTestsCheck = el('input');
      skipTestsCheck.type = 'checkbox';
      skipTestsCheck.checked = Boolean(cfg.skipTests);

      const saveBtn = btn('Save & Hot-Reload Sandbox', async () => {
        saveBtn.disabled = true;
        setMsg('Validating sandbox parameters and hot-reloading runner…');
        try {
          const payload = {
            autoDetect: false,
            isolationMode: modeSel.value,
            buildTool: toolSel.value,
            timeoutSeconds: Number(timeoutInput.value) || 900,
            skipTests: skipTestsCheck.checked
          };
          if (imgInput.value.trim()) payload.baseImage = imgInput.value.trim();
          if (m2Input.value.trim()) payload.m2Repository = m2Input.value.trim();
          if (gradleInput.value.trim()) payload.gradleHome = gradleInput.value.trim();
          if (npmInput.value.trim()) payload.npmCache = npmInput.value.trim();
          if (pipInput.value.trim()) payload.pipWheelhouse = pipInput.value.trim();
          if (goInput.value.trim()) payload.goModCache = goInput.value.trim();
          const res = await api('/repairs/sandbox/setup', payload, signal, 'POST', 30000);
          renderState(res);
          setMsg('Sandbox configuration saved and hot-reloaded.');
          window.EvoToast?.push?.({ kind: 'ok', title: 'Sandbox configuration updated' });
          onUpdated(res);
        } catch (err) {
          if (!signal?.aborted) setMsg(err.message || 'Invalid sandbox configuration.', true);
        } finally {
          saveBtn.disabled = false;
        }
      }, 'button primary');

      append(form,
        append(el('label', 'rs-field'), el('span', 'small muted', 'Isolation mode'), modeSel),
        append(el('label', 'rs-field'), el('span', 'small muted', 'Target build toolchain'), toolSel),
        append(el('label', 'rs-field rs-field-span'), el('span', 'small muted', 'Pinned base image (@sha256:64hex)'), imgInput),
        append(el('label', 'rs-field'), el('span', 'small muted', 'Maven offline cache (.m2)'), m2Input),
        append(el('label', 'rs-field'), el('span', 'small muted', 'Gradle offline home'), gradleInput),
        append(el('label', 'rs-field'), el('span', 'small muted', 'Node / npm offline cache'), npmInput),
        append(el('label', 'rs-field'), el('span', 'small muted', 'Python wheelhouse'), pipInput),
        append(el('label', 'rs-field'), el('span', 'small muted', 'Go module cache'), goInput),
        append(el('label', 'rs-field'), el('span', 'small muted', 'Timeout (seconds, 30–1800)'), timeoutInput),
        append(el('label', 'gh-confirm-check rs-field-span'), skipTestsCheck, el('span', '', 'Skip test execution inside sandbox (compile check only — weakens behavioral diff to UNKNOWN_UNTESTED)')),
        append(el('div', 'rs-action-row rs-field-span'), saveBtn)
      );
      details.append(form);

      body.append(actionBar, details);
      if (setup?.lastProbe) {
        renderProbeResults(probeHost, setup.lastProbe);
      }
    }

    async function refresh() {
      setMsg('Loading sandbox runner configuration…');
      try {
        const res = await api('/repairs/sandbox/setup', undefined, signal, 'GET', 15000);
        renderState(res);
        setMsg('');
        return res;
      } catch (err) {
        if (signal?.aborted) return null;
        if (err?.status === 403 || String(err?.code || '').includes('ADMIN')) {
          head.replaceChildren(
            append(el('div', 'rs-sandbox-title-group'),
              el('strong', '', 'Isolated Sandbox Runner'),
              el('span', 'badge warn', 'ADMIN Role Required'))
          );
          body.replaceChildren(el('div', 'rs-inline-notice is-warn',
            'Workspace ADMIN permission is required to inspect or change isolated sandbox runner settings.'));
          setMsg('');
          return null;
        }
        setMsg(err?.message || 'Could not load sandbox setup.', true);
        return null;
      }
    }

    void refresh();
    return { refresh, getState: () => currentSetup };
  }

  window.EvoSandboxSetup = {
    renderPanel,
    renderStatusBadge
  };
})();
