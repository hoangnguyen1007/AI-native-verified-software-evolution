/* 5-Step Guided Repair, Sandbox Verification, Impact Report & Explicit Confirmation Gate Studio.
 * Strict zero-XSS DOM construction; hard-blocks applying REJECTED patches; requires explicit user
 * confirmation for local apply and separate confirmation before GitHub PR push. */
(() => {
  'use strict';
  const M = window.EvoModel;
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

  function renderMultiFileDiff(patchText, fallbackPath = 'source', initialMode = 'unified') {
    const summary = M.summarizePatch
      ? M.summarizePatch(patchText, fallbackPath)
      : { totalFiles: 1, totalAdditions: 0, totalDeletions: 0, files: [] };
    const wrap = el('div', 'rs-diff-shell');
    let viewMode = initialMode;
    let selectedFileIdx = 0;

    const bar = el('div', 'rs-diff-toolbar');
    const summaryMeta = el('div', 'rs-diff-summary');
    summaryMeta.append(
      el('strong', '', `${summary.totalFiles} file${summary.totalFiles === 1 ? '' : 's'} changed`),
      el('span', 'diff-stat-pill is-add', `+${summary.totalAdditions}`),
      el('span', 'diff-stat-pill is-del', `-${summary.totalDeletions}`)
    );

    const modeGroup = el('div', 'rs-mode-toggle');
    modeGroup.setAttribute('role', 'group');
    modeGroup.setAttribute('aria-label', 'Diff view mode');
    const unifiedBtn = btn('Unified', () => { viewMode = 'unified'; renderBody(); }, 'chip active');
    const splitBtn = btn('Side-by-side', () => { viewMode = 'split'; renderBody(); }, 'chip');
    modeGroup.append(unifiedBtn, splitBtn);
    bar.append(summaryMeta, modeGroup);
    wrap.append(bar);

    const fileTabs = el('div', 'rs-file-list');
    fileTabs.setAttribute('role', 'tablist');
    fileTabs.setAttribute('aria-label', 'Modified files');
    const codePane = el('div', 'rs-diff-pane');

    if (summary.files.length > 0) {
      wrap.append(fileTabs, codePane);
    } else {
      const pre = el('pre', 'repair-patch', patchText || '');
      wrap.append(pre);
      return wrap;
    }

    function renderFileTabs() {
      fileTabs.replaceChildren();
      summary.files.forEach((file, idx) => {
        const badgeCls = file.changeType === 'CREATE' ? 'is-create' : file.changeType === 'DELETE' ? 'is-delete' : 'is-modify';
        const tab = btn('', () => { selectedFileIdx = idx; renderBody(); }, 'rs-file-tab' + (idx === selectedFileIdx ? ' is-active' : ''));
        tab.setAttribute('role', 'tab');
        tab.setAttribute('aria-selected', String(idx === selectedFileIdx));
        tab.append(
          el('span', 'rs-change-badge ' + badgeCls, file.changeType),
          el('span', 'rs-file-path', file.path),
          el('span', 'rs-file-delta', `+${file.additions} -${file.deletions}`)
        );
        fileTabs.append(tab);
      });
    }

    function renderBody() {
      unifiedBtn.classList.toggle('active', viewMode === 'unified');
      splitBtn.classList.toggle('active', viewMode === 'split');
      renderFileTabs();

      const file = summary.files[selectedFileIdx] || summary.files[0];
      if (!file) {
        codePane.replaceChildren(el('p', 'small muted', 'Empty diff.'));
        return;
      }

      if (viewMode === 'unified') {
        const table = el('div', 'rs-unified-table');
        table.setAttribute('role', 'region');
        table.setAttribute('aria-label', 'Unified patch for ' + file.path);
        for (const hunk of file.hunks) {
          if (hunk.header) {
            table.append(el('div', 'rs-diff-hunk-row', hunk.header));
          }
          for (const ln of hunk.lines) {
            const row = el('div', 'rs-diff-row is-' + ln.type);
            const prefix = ln.type === 'add' ? '+' : ln.type === 'del' ? '-' : ln.type === 'meta' ? '' : ' ';
            row.append(
              el('span', 'rs-ln-num', ln.oldNum != null ? String(ln.oldNum) : ''),
              el('span', 'rs-ln-num', ln.newNum != null ? String(ln.newNum) : ''),
              el('span', 'rs-ln-sign', prefix),
              el('span', 'rs-ln-code', ln.text || ' ')
            );
            table.append(row);
          }
        }
        codePane.replaceChildren(table);
        return;
      }

      // Side-by-side split view
      const splitTable = el('table', 'rs-split-table');
      splitTable.setAttribute('aria-label', 'Side-by-side patch for ' + file.path);
      const tbody = el('tbody');
      for (const r of file.rows) {
        if (r.kind === 'hunk') {
          const tr = el('tr', 'rs-split-hunk');
          const td = el('td', '', r.header);
          td.colSpan = 4;
          tr.append(td);
          tbody.append(tr);
          continue;
        }
        const tr = el('tr');
        const lNum = el('td', 'rs-split-num is-' + r.leftType, r.leftNum != null ? String(r.leftNum) : '');
        const lCode = el('td', 'rs-split-code is-' + r.leftType, r.leftText || ' ');
        const rNum = el('td', 'rs-split-num is-' + r.rightType, r.rightNum != null ? String(r.rightNum) : '');
        const rCode = el('td', 'rs-split-code is-' + r.rightType, r.rightText || ' ');
        tr.append(lNum, lCode, rNum, rCode);
        tbody.append(tr);
      }
      splitTable.append(tbody);
      const scroll = append(el('div', 'rs-split-scroll'), splitTable);
      codePane.replaceChildren(scroll);
    }

    renderBody();
    return wrap;
  }

  function renderVerificationReportCard(report, { onDownloadMarkdown, onProceedToApply }) {
    const trust = M.repairTrustMeta(report?.verdict, report?.trustLabel);
    const score = M.formatScoreV2Delta(report?.scoreV2Delta || report?.scoreDelta);
    const vDelta = report?.violationsDelta || report?.violationDelta || {};
    const gDelta = report?.capabilityGapsDelta || report?.capabilityGapDelta || {};
    const build = report?.build || report?.sandbox || report?.isolatedProof || {};
    const tests = report?.tests || report?.sandbox || report?.isolatedProof || {};
    const countOf = v => Array.isArray(v) ? v.length : Number(v ?? 0);

    const card = el('section', 'rs-report-card');
    card.dataset.verdict = trust.verdict;

    // Banner
    const banner = el('div', 'rs-verdict-banner is-' + trust.verdictTone);
    const left = el('div', 'rs-verdict-main');
    const isolationBackend = report?.isolationBackend || report?.sandbox?.isolationMode || '';
    left.append(
      append(el('div', 'rs-verdict-badges'),
        el('span', 'badge ' + (trust.verdictTone === 'good' ? 'good' : trust.verdictTone === 'bad' ? 'bad' : 'warn'), trust.verdictShort),
        el('span', 'rs-trust-pill is-' + trust.trustTone, trust.trustTitle),
        isolationBackend ? el('span', 'badge', 'Sandbox: ' + isolationBackend) : null
      ),
      el('strong', 'rs-verdict-headline', trust.verdictLabel),
      el('p', 'small muted', report?.executiveSummary || trust.trustSummary)
    );
    banner.append(left);
    card.append(banner);

    // 4-Tile KPI Grid
    const kpiGrid = el('div', 'rs-kpi-grid');

    // 1. Build & Tests
    const sandboxRan = build.executed !== false;
    const compileOk = build.compiled ?? (sandboxRan && build.compileExit === 0 ? true : (sandboxRan && build.compileExit != null && build.compileExit >= 0 ? false : null));
    const testsRun = sandboxRan ? (tests.run ?? tests.testsRun ?? null) : null;
    const testsFailed = Number(tests.failed ?? tests.testsFailed ?? 0) + Number(tests.errors ?? tests.testsErrors ?? 0);
    const buildStatus = compileOk === true ? 'Compiled ✓' : compileOk === false ? 'Compile FAILED' : (build.status || 'Not run');
    const testText = testsRun != null ? `${testsRun} tests · ${testsFailed} failed` : (tests.status || 'Not executed');
    const buildTile = el('article', 'rs-kpi-tile');
    buildTile.append(
      el('span', 'rs-kpi-label', 'Build & Tests'),
      el('strong', 'rs-kpi-value ' + (compileOk === false || testsFailed > 0 ? 'is-bad' : compileOk === true ? 'is-good' : 'is-warn'), buildStatus),
      el('span', 'small muted', testText + (build.durationMs ? ` · ${(build.durationMs / 1000).toFixed(1)}s` : ''))
    );

    // 2. Score v2 Delta
    const scoreTile = el('article', 'rs-kpi-tile');
    scoreTile.append(
      el('span', 'rs-kpi-label', 'Health Score v2 Delta'),
      el('strong', 'rs-kpi-value is-' + score.tone, score.available ? `${score.beforeText} → ${score.afterText} (${score.deltaText})` : 'Unavailable'),
      el('span', 'small muted', score.beforeBand && score.afterBand ? `${score.beforeBand} → ${score.afterBand}` : '7-dimension structural score')
    );

    // 3. Violations Delta
    const resolved = countOf(vDelta.resolved);
    const introduced = countOf(vDelta.introduced);
    const persisted = countOf(vDelta.persisted);
    const violTile = el('article', 'rs-kpi-tile');
    violTile.append(
      el('span', 'rs-kpi-label', 'Violations Delta'),
      el('strong', 'rs-kpi-value ' + (introduced > 0 ? 'is-bad' : resolved > 0 ? 'is-good' : 'is-neutral'),
        `${resolved} resolved · ${introduced} introduced`),
      el('span', 'small muted', persisted ? `${persisted} persisted in scope` : 'Zero new regressions introduced')
    );

    // 4. Capability Gaps Delta (Closed Denominator)
    const gapBefore = gDelta.before ?? gDelta.beforeCount;
    const gapAfter = gDelta.after ?? gDelta.afterCount;
    const gapDiff = gDelta.delta ?? (gapBefore != null && gapAfter != null ? gapAfter - gapBefore : null);
    const gapTile = el('article', 'rs-kpi-tile');
    gapTile.append(
      el('span', 'rs-kpi-label', 'Capability Gaps Delta'),
      el('strong', 'rs-kpi-value ' + (gapDiff != null && gapDiff > 0 ? 'is-warn' : gapDiff != null && gapDiff < 0 ? 'is-good' : 'is-neutral'),
        gapBefore != null && gapAfter != null ? `${gapBefore} → ${gapAfter} (${gapDiff > 0 ? '+' : ''}${gapDiff})` : (gapDiff != null ? `${gapDiff > 0 ? '+' : ''}${gapDiff}` : 'No change recorded')),
      el('span', 'small muted', 'Closed reporting denominator')
    );

    kpiGrid.append(buildTile, scoreTile, violTile, gapTile);
    card.append(kpiGrid);

    // Score v2 D1–D7 Dimension Deltas
    if (score.dimensions.length) {
      const dimBox = el('details', 'rs-dim-deltas');
      dimBox.open = true;
      dimBox.append(el('summary', '', `Dimension Breakdown (${score.dimensions.length} dimensions)`));
      const dimGrid = el('div', 'rs-dim-grid');
      for (const d of score.dimensions) {
        const row = el('div', 'rs-dim-row');
        row.append(
          el('span', 'rs-dim-name', d.displayName),
          el('span', 'rs-dim-vals', `${d.before != null ? d.before.toFixed(1) : '—'} → ${d.after != null ? d.after.toFixed(1) : '—'}`),
          el('strong', 'rs-dim-delta is-' + d.tone, d.deltaText)
        );
        dimGrid.append(row);
      }
      dimBox.append(dimGrid);
      card.append(dimBox);
    }

    // 3-State Sandboxed Behavioral Differential Check & Reproducible Witness Viewer (Moonshot #1)
    if (report?.behavioralDiff && typeof report.behavioralDiff === 'object') {
      const bd = report.behavioralDiff;
      const firstWitness = Array.isArray(bd.witnesses) && bd.witnesses.length ? bd.witnesses[0] : null;
      const firstIntent = firstWitness?.intentClassification || firstWitness?.intent || '';
      const bMeta = M.behavioralVerdictMeta
        ? M.behavioralVerdictMeta(bd.verdict, firstIntent)
        : { verdict: String(bd.verdict || 'UNKNOWN_UNTESTED'), label: String(bd.verdict || 'UNKNOWN_UNTESTED'), shortLabel: String(bd.verdict || 'UNKNOWN_UNTESTED'), tone: 'warn', summary: '' };
      const probesGen = Number(bd.proposedProbesGenerated ?? bd.probesSynthesized ?? 0);
      const probesExec = Number(bd.proposedProbesExecuted ?? bd.probesExecuted ?? 0);
      const bdSec = el('section', 'rs-intel-card rs-behavioral-card');
      bdSec.dataset.behavioralVerdict = bMeta.verdict;
      const bdHead = el('div', 'rs-verdict-badges');
      append(bdHead,
        el('h4', 'rs-subhead', '3-State Behavioral Differential Check'),
        el('span', 'badge ' + (bMeta.tone === 'good' ? 'good' : bMeta.tone === 'bad' ? 'bad' : 'warn'), bMeta.shortLabel),
        probesGen > 0 ? el('span', 'badge', `Probes: ${probesExec}/${probesGen}`) : null,
        bd.unionCoveragePct != null
          ? el('span', 'badge', `Union coverage: ${Number(bd.unionCoveragePct).toFixed(1)}%`)
          : null
      );
      bdSec.append(
        bdHead,
        el('p', 'small', bd.summary || bd.explanation || bMeta.summary),
        el('p', 'small muted',
          `Baseline: ${bd.baselineTestsRun ?? 0} run (${bd.baselineTestsFailed ?? 0} failed) · Candidate: ${bd.candidateTestsRun ?? 0} run (${bd.candidateTestsFailed ?? 0} failed)`)
      );
      if (Array.isArray(bd.witnesses) && bd.witnesses.length) {
        const wList = el('div', 'rs-witness-list');
        for (const w of bd.witnesses) {
          const wCard = el('article', 'rs-witness-card');
          const intentStr = String(w.intentClassification || w.intent || 'UNKNOWN').toUpperCase();
          const intentTone = intentStr === 'INTENDED_FIX' ? 'good' : intentStr === 'UNINTENDED_REGRESSION' ? 'bad' : 'warn';
          wCard.append(
            append(el('div', 'rs-verdict-badges'),
              el('strong', 'mono small', w.testPath || 'sandbox-suite'),
              el('span', 'badge ' + intentTone, intentStr),
              el('span', 'badge', `exit ${w.baseExit} → ${w.candExit}`),
              w.outputSha256 ? el('span', 'small mono muted', M.shortDigest(w.outputSha256)) : null)
          );
          if (w.outputExcerpt) {
            const det = el('details', 'rs-log-details');
            det.append(el('summary', '', 'Reproducible witness output (redacted)'), el('pre', 'rs-log-pre', String(w.outputExcerpt)));
            wCard.append(det);
          }
          wList.append(wCard);
        }
        bdSec.append(wList);
      }
      card.append(bdSec);
    }

    // Spring Truth-Region Preservation Check (Moonshot #1)
    if (report?.springPreservation && typeof report.springPreservation === 'object') {
      const sp = report.springPreservation;
      const sMeta = M.springPreservationMeta
        ? M.springPreservationMeta(sp.status)
        : { status: String(sp.status || 'NOT_APPLICABLE'), label: String(sp.status || 'NOT_APPLICABLE'), shortLabel: String(sp.status || 'NOT_APPLICABLE'), tone: 'muted', summary: '' };
      const spSec = el('section', 'rs-intel-card rs-spring-card');
      spSec.dataset.springStatus = sMeta.status;
      const baseFacts = sp.baselineFactCount ?? sp.baselineMustRegions ?? 0;
      const candFacts = sp.candidateFactCount ?? sp.candidateMustRegions ?? 0;
      const preservedFacts = sp.preservedFactCount ?? Math.min(baseFacts, candFacts);
      const baseWorlds = sp.baselineWorldCount ?? sp.baselineMayRegions ?? 0;
      const candWorlds = sp.candidateWorldCount ?? sp.candidateMayRegions ?? 0;
      append(spSec,
        append(el('div', 'rs-verdict-badges'),
          el('h4', 'rs-subhead', 'Spring Truth-Region Preservation'),
          el('span', 'badge ' + (sMeta.tone === 'good' ? 'good' : sMeta.tone === 'bad' ? 'bad' : ''), sMeta.shortLabel)),
        el('p', 'small', sp.summary || sMeta.summary),
        el('p', 'small muted',
          `Truth-region facts: ${baseFacts} → ${candFacts} (${preservedFacts} preserved) · Worlds: ${baseWorlds} → ${candWorlds}`)
      );
      if (Array.isArray(sp.drifts) && sp.drifts.length) {
        const dList = el('ul', 'rs-drift-list');
        for (const d of sp.drifts) {
          const li = el('li', 'rs-drift-item');
          li.append(
            append(el('div', 'rs-verdict-badges'),
              el('span', 'badge bad', d.factKind || d.kind || 'DRIFT'),
              el('strong', 'mono small', d.symbolLabel || d.subject || 'spring-binding'),
              el('span', 'badge warn', `${d.beforeClassification || d.baselineClassification || '?'} → ${d.afterClassification || d.candidateClassification || '?'}`)),
            d.detail ? el('p', 'small muted', d.detail) : null
          );
          dList.append(li);
        }
        spSec.append(dList);
      }
      card.append(spSec);
    }

    // Closed-Loop Repair Rounds Timeline (Moonshot #1)
    if (Array.isArray(report?.rounds) && report.rounds.length) {
      const rSec = el('section', 'rs-intel-card rs-rounds-card');
      rSec.append(el('h4', 'rs-subhead', `Closed-Loop Repair Rounds (${report.rounds.length})`));
      const ol = el('ol', 'rs-round-timeline');
      for (const rd of report.rounds) {
        const li = el('li', 'rs-round-item');
        const rVerdict = String(rd.verdict || 'UNVERIFIED').toUpperCase();
        const rTone = (rVerdict === 'VERIFIED' || rVerdict === 'VERIFIED_DEMO') ? 'good' : rVerdict === 'REJECTED' ? 'bad' : 'warn';
        const rowHead = el('div', 'rs-verdict-badges');
        append(rowHead,
          el('strong', '', `Round ${rd.round}`),
          el('span', 'badge ' + rTone, rVerdict),
          rd.compileExit != null && rd.compileExit >= 0 ? el('span', 'badge ' + (rd.compileExit === 0 ? 'good' : 'bad'), `compile=${rd.compileExit}`) : null,
          rd.testExit != null && rd.testExit >= 0 ? el('span', 'badge ' + (rd.testExit === 0 ? 'good' : 'bad'), `test=${rd.testExit}`) : null,
          rd.toolCallsCount > 0 ? el('span', 'badge', `Read-only tools: ${rd.toolCallsCount}`) : null,
          rd.proposalId ? el('span', 'small mono muted', M.shortDigest(rd.proposalId)) : null
        );
        li.append(rowHead);
        if (rd.feedbackSummary) {
          li.append(el('p', 'small muted', rd.feedbackSummary));
        }
        if (Array.isArray(rd.toolTrace) && rd.toolTrace.length) {
          const tDet = el('details', 'rs-log-details');
          tDet.append(el('summary', '', `Read-only agent tool calls (${rd.toolTrace.length})`));
          const tUl = el('ul', 'rs-tool-trace-list');
          for (const t of rd.toolTrace) {
            tUl.append(el('li', 'small mono', `${t.tool || 'tool'}: ${t.summary || ''}`));
          }
          tDet.append(tUl);
          li.append(tDet);
        }
        ol.append(li);
      }
      rSec.append(ol);
      card.append(rSec);
    }

    // Verification checks ledger
    const checks = Array.isArray(report?.checks) ? report.checks : [];
    if (checks.length) {
      const checkList = el('ul', 'rs-check-list');
      for (const c of checks) {
        const st = String(c.status || 'NOT_RUN').toUpperCase();
        const cls = st === 'PASSED' ? 'is-pass' : st === 'FAILED' ? 'is-fail' : 'is-warn';
        const li = el('li', 'rs-check-item ' + cls);
        li.append(
          el('span', 'rs-check-icon', st === 'PASSED' ? '✓' : st === 'FAILED' ? '✕' : '•'),
          append(el('div'),
            el('strong', '', `${c.id || c.name || 'check'} · ${st}`),
            c.reason ? el('p', 'small muted', c.reason) : null)
        );
        checkList.append(li);
      }
      card.append(el('h4', 'rs-subhead', 'Verification Checks'), checkList);
    }

    // Collapsible stdout/stderr summary
    const logTail = report?.logTail || report?.logs || '';
    if (logTail) {
      const logDetails = el('details', 'rs-log-details');
      logDetails.append(
        el('summary', '', 'Sandbox stdout / stderr summary'),
        el('pre', 'rs-log-pre', Array.isArray(logTail) ? logTail.join('\n') : String(logTail))
      );
      card.append(logDetails);
    }

    // Provenance & Actions
    if (report?.provenance) {
      const prov = report.provenance;
      const bits = [
        prov.baselineGraph ? `Baseline ${M.shortDigest(prov.baselineGraph)}` : null,
        prov.candidateGraph ? `Candidate ${M.shortDigest(prov.candidateGraph)}` : null,
        prov.patchDigest ? `Patch ${M.shortDigest(prov.patchDigest)}` : null
      ].filter(Boolean);
      if (bits.length) card.append(el('p', 'small mono muted', bits.join(' · ')));
    }

    const actions = el('div', 'rs-action-row');
    if (typeof onDownloadMarkdown === 'function') {
      actions.append(btn('Download Report (.md)', onDownloadMarkdown, 'button subtle'));
    }
    if (typeof onProceedToApply === 'function') {
      const proceedBtn = btn(
        trust.isRejected ? 'Review Rejection & Guardrails →' : 'Continue to Confirmation Gate →',
        onProceedToApply,
        trust.isRejected ? 'button subtle' : 'button primary'
      );
      actions.append(proceedBtn);
    }
    card.append(actions);
    return card;
  }

  function mountRepairStudio(container, ctx) {
    const {
      env, reportId, findings, api, resolveLabels, openCapturedSource,
      inspectFinding, askAboutFinding, downloadBlob, capabilities, setupId, isGitHubRepo
    } = ctx;

    const studio = {
      mode: findings.length ? 'finding' : 'adhoc', // 'finding' | 'adhoc'
      step: 1, // 1..5
      selectedFinding: findings[0] || null,
      diagnosis: null,
      options: null,
      proposal: null,
      verifyJob: null,
      verifyReport: null,
      verifySse: null,
      applyPreview: null,
      applyReceipt: null,
      prPlan: null,
      prResult: null,
      unverifiedAck: false,
      adhocPrompt: '',
      adhocFiles: '',
      sandboxOpen: false,
      historyOpen: false,
      sandboxSetup: null,
      closedLoopEnabled: true,
      maxRounds: 3,
      runBehavioralDiff: true,
      proposeCharacterizationTests: false,
      liveRounds: []
    };

    const stopVerifyStream = () => {
      if (studio.verifySse) {
        try { studio.verifySse.close(); } catch (_) {}
        studio.verifySse = null;
      }
    };
    env.signal?.addEventListener('abort', stopVerifyStream, { once: true });

    const root = el('section', 'rs-studio');
    root.setAttribute('aria-label', 'Guided Repair and Sandbox Verification Studio');

    const header = el('header', 'rs-studio-header');
    const titleWrap = append(el('div'),
      el('h3', 'rs-studio-title', 'Guided Repair & Sandbox Verification Studio'),
      el('p', 'small muted', 'Diagnose with cited evidence, generate a compiler-checked patch, verify in an isolated sandbox, and apply only after explicit confirmation.'));
    const headerModeBtns = el('div', 'rs-header-actions');
    const findingModeBtn = btn('Finding Repair', () => {
      studio.mode = 'finding';
      studio.step = 1;
      renderAll();
    }, 'chip' + (studio.mode === 'finding' ? ' active' : ''));
    const adhocModeBtn = btn('Ad-Hoc Refactoring Prompt', () => {
      studio.mode = 'adhoc';
      studio.step = 1;
      renderAll();
    }, 'chip' + (studio.mode === 'adhoc' ? ' active' : ''));

    const sandboxDrawer = el('div', 'rs-drawer-host');
    sandboxDrawer.hidden = true;
    const historyDrawer = el('div', 'rs-drawer-host');
    historyDrawer.hidden = true;

    const sandboxToggleBtn = btn('Sandbox Setup', () => {
      studio.sandboxOpen = !studio.sandboxOpen;
      sandboxToggleBtn.classList.toggle('active', studio.sandboxOpen);
      sandboxToggleBtn.setAttribute('aria-expanded', String(studio.sandboxOpen));
      if (studio.sandboxOpen) {
        studio.historyOpen = false;
        historyToggleBtn.classList.remove('active');
        historyToggleBtn.setAttribute('aria-expanded', 'false');
        historyDrawer.hidden = true;
        historyDrawer.replaceChildren();
        sandboxDrawer.hidden = false;
        if (window.EvoSandboxSetup?.renderPanel) {
          window.EvoSandboxSetup.renderPanel(sandboxDrawer, {
            api,
            signal: env.signal,
            compact: true,
            onUpdated: updated => {
              studio.sandboxSetup = updated;
              if (capabilities && updated) {
                capabilities.repairIsolatedRunnerConfigured = Boolean(updated.configured);
                capabilities.repairSandboxMode = updated.configured ? updated.isolationMode : 'PASSIVE_REANALYSIS_ONLY';
              }
              if (studio.step === 3) renderStage();
            }
          });
        }
      } else {
        sandboxDrawer.hidden = true;
        sandboxDrawer.replaceChildren();
      }
    }, 'chip rs-sandbox-toggle');
    sandboxToggleBtn.setAttribute('aria-expanded', 'false');

    async function renderJobHistoryDrawer() {
      historyDrawer.replaceChildren(el('p', 'small muted', 'Loading persisted repair verification jobs…'));
      try {
        const res = await api('/repairs/jobs', undefined, env.signal, 'GET', 15000);
        const items = Array.isArray(res?.items) ? res.items : (Array.isArray(res) ? res : []);
        const card = el('section', 'rs-history-card');
        card.append(el('strong', '', `Persisted Verification Jobs (${items.length})`));
        if (!items.length) {
          card.append(el('p', 'small muted', 'No verification jobs persisted in jobs.db yet.'));
        } else {
          const list = el('ul', 'rs-history-list');
          for (const j of items.slice(0, 12)) {
            const stageStr = String(j.stage || j.status || 'QUEUED').toUpperCase();
            const isInterrupted = stageStr === 'INTERRUPTED_ON_RESTART';
            const verdictStr = String(j.report?.verdict || j.verdict || stageStr).toUpperCase();
            const tone = (verdictStr === 'VERIFIED' || verdictStr === 'VERIFIED_DEMO') ? 'good'
              : (verdictStr === 'REJECTED' || stageStr === 'FAILED') ? 'bad' : 'warn';
            const li = el('li', 'rs-history-item');
            const openBtn = btn(j.report ? 'Open Report' : 'Inspect Job', async () => {
              try {
                const detail = await api(`/repairs/jobs/${encodeURIComponent(j.jobId)}`, undefined, env.signal, 'GET', 15000);
                studio.verifyJob = detail;
                studio.applyPreview = null;
                studio.applyPreviewProposalId = null;
                studio.applyReceipt = null;
                studio.prPlan = null;
                studio.prResult = null;
                if (detail?.report || detail?.verdict) {
                  studio.verifyReport = unwrapJobReport(detail);
                  studio.step = 4;
                } else {
                  studio.verifyReport = null;
                  const evs = Array.isArray(detail?.events) ? detail.events : [];
                  studio.liveRounds = evs.map(ev => ({
                    round: Number(ev?.detail?.round ?? ev?.round ?? 0) || null,
                    stage: String(ev?.stage || '').toUpperCase(),
                    message: ev?.message || ev?.stage || ''
                  })).filter(r => r.stage || r.message);
                  studio.step = 3;
                }
                studio.historyOpen = false;
                historyToggleBtn.classList.remove('active');
                historyToggleBtn.setAttribute('aria-expanded', 'false');
                historyDrawer.hidden = true;
                historyDrawer.replaceChildren();
                renderAll();
              } catch (_) {}
            }, 'button subtle');
            const historyBehav = j.behavioralVerdict || j.report?.behavioralDiff?.verdict || '';
            li.append(
              append(el('div', 'rs-verdict-badges'),
                el('span', 'badge ' + tone, isInterrupted ? 'INTERRUPTED_ON_RESTART' : verdictStr),
                el('span', 'small mono', M.shortDigest(j.proposalId || j.jobId)),
                j.rounds?.length ? el('span', 'badge', `${j.rounds.length} round(s)`) : null,
                historyBehav ? el('span', 'badge', historyBehav) : null),
              openBtn
            );
            list.append(li);
          }
          card.append(list);
        }
        historyDrawer.replaceChildren(card);
      } catch (err) {
        if (!env.signal.aborted) {
          historyDrawer.replaceChildren(el('div', 'rs-inline-notice is-warn', err.message || 'Could not load persisted job history.'));
        }
      }
    }

    const historyToggleBtn = btn('Job History', () => {
      studio.historyOpen = !studio.historyOpen;
      historyToggleBtn.classList.toggle('active', studio.historyOpen);
      historyToggleBtn.setAttribute('aria-expanded', String(studio.historyOpen));
      if (studio.historyOpen) {
        studio.sandboxOpen = false;
        sandboxToggleBtn.classList.remove('active');
        sandboxToggleBtn.setAttribute('aria-expanded', 'false');
        sandboxDrawer.hidden = true;
        sandboxDrawer.replaceChildren();
        historyDrawer.hidden = false;
        void renderJobHistoryDrawer();
      } else {
        historyDrawer.hidden = true;
        historyDrawer.replaceChildren();
      }
    }, 'chip rs-history-toggle');
    historyToggleBtn.setAttribute('aria-expanded', 'false');

    headerModeBtns.append(findingModeBtn, adhocModeBtn, sandboxToggleBtn, historyToggleBtn);
    header.append(titleWrap, headerModeBtns);

    // 5-step stepper rail
    const stepper = el('ol', 'rs-stepper');
    stepper.setAttribute('aria-label', 'Repair workflow steps');
    const stepDefs = [
      [1, '1. Diagnosis'],
      [2, '2. Code Patch'],
      [3, '3. Sandbox Verify'],
      [4, '4. Impact Report'],
      [5, '5. Confirm & Apply']
    ];

    const stageHost = el('div', 'rs-stage-host');
    root.append(header, sandboxDrawer, historyDrawer, stepper, stageHost);
    container.replaceChildren(root);

    function highestUnlockedStep() {
      if (studio.verifyReport) return 5;
      if (studio.proposal?.status === 'PROPOSED') return 5;
      if (studio.diagnosis?.status === 'CITATIONS_VALIDATED') return 2;
      return 1;
    }

    function renderStepper() {
      findingModeBtn.classList.toggle('active', studio.mode === 'finding');
      adhocModeBtn.classList.toggle('active', studio.mode === 'adhoc');
      stepper.replaceChildren();
      const maxStep = highestUnlockedStep();
      for (const [num, lbl] of stepDefs) {
        const li = el('li', 'rs-step-item');
        const unlocked = num <= maxStep;
        const done = (num === 1 && (studio.diagnosis?.status === 'CITATIONS_VALIDATED' || studio.proposal?.status === 'PROPOSED'))
          || (num === 2 && studio.proposal?.status === 'PROPOSED')
          || (num === 3 && Boolean(studio.verifyReport))
          || (num === 4 && Boolean(studio.verifyReport))
          || (num === 5 && Boolean(studio.applyReceipt || studio.prResult));
        const b = btn(lbl, () => {
          if (!unlocked) return;
          studio.step = num;
          renderStage();
          renderStepper();
        }, 'rs-step-btn' + (studio.step === num ? ' is-current' : '') + (done ? ' is-done' : ''));
        b.disabled = !unlocked;
        if (studio.step === num) b.setAttribute('aria-current', 'step');
        li.append(b);
        stepper.append(li);
      }
    }

    function selectFinding(item) {
      stopVerifyStream();
      studio.mode = 'finding';
      studio.selectedFinding = item;
      studio.diagnosis = null;
      studio.options = null;
      studio.proposal = null;
      studio.verifyJob = null;
      studio.verifyReport = null;
      studio.applyPreview = null;
      studio.applyReceipt = null;
      studio.prPlan = null;
      studio.prResult = null;
      studio.unverifiedAck = false;
      studio.step = 1;
      renderAll();
    }

    /* ── STEP 1: Diagnosis or Ad-Hoc Prompt ───────────────────────────────── */
    function renderStep1() {
      if (studio.mode === 'adhoc' || !studio.selectedFinding) {
        const box = el('div', 'rs-card');
        box.append(
          el('h4', '', 'Ad-Hoc Architecture Repair Proposal'),
          el('p', 'small muted', 'Describe the refactoring or structural fix you want. Evo will propose a compiler-checked multi-file patch tied to this captured snapshot, then verify it in an isolated sandbox.')
        );
        const promptField = el('textarea', 'rs-textarea');
        promptField.rows = 4;
        promptField.placeholder = 'Example: Extract an OrderGateway interface to break the circular dependency between OrderService and BillingController…';
        promptField.value = studio.adhocPrompt;
        promptField.addEventListener('input', () => { studio.adhocPrompt = promptField.value; });

        const filesInput = el('input', 'rs-input');
        filesInput.placeholder = 'Optional — leave blank to auto-discover via RepoMap (Trigram+IDF+PPR), or enter comma-separated paths';
        filesInput.value = studio.adhocFiles;
        filesInput.addEventListener('input', () => { studio.adhocFiles = filesInput.value; });

        const statusLine = el('p', 'small muted');
        statusLine.setAttribute('role', 'status');

        const genBtn = btn('Generate Ad-Hoc Patch →', async () => {
          const prompt = promptField.value.trim();
          if (!prompt) {
            statusLine.textContent = 'Describe the desired code change first.';
            promptField.focus();
            return;
          }
          genBtn.disabled = true;
          statusLine.textContent = 'Generating compiler-checked multi-file patch…';
          try {
            const targetPaths = filesInput.value.split(',').map(s => s.trim()).filter(Boolean);
            const outcome = await api(`/graphs/${env.graph}/repair-proposal/ad-hoc`, {
              context: env.context,
              prompt,
              ...(targetPaths.length ? { files: targetPaths } : {}),
              maxRepairAttempts: 3
            }, env.signal, 'POST', 120000);
            studio.proposal = outcome;
            studio.verifyReport = null;
            studio.applyPreview = null;
            studio.step = 2;
            renderAll();
          } catch (err) {
            if (!env.signal.aborted) statusLine.textContent = err.message || 'Ad-hoc proposal unavailable.';
          } finally {
            genBtn.disabled = false;
          }
        }, 'button primary');

        box.append(
          append(el('label', 'rs-field'), el('span', 'small muted', 'Refactoring goal'), promptField),
          append(el('label', 'rs-field'), el('span', 'small muted', 'Focus files (optional)'), filesInput),
          append(el('div', 'rs-action-row'), genBtn),
          statusLine
        );
        stageHost.replaceChildren(box);
        return;
      }

      const item = studio.selectedFinding;
      const finding = item.result || {};
      const rule = finding.rule?.header || finding.rule || {};
      const bundle = finding.bundle || {};
      const box = el('div', 'rs-card');

      const head = el('div', 'rs-finding-head');
      const clsStr = finding.classification || 'MUST';
      const scopePlain = M.explainMetric?.(clsStr)?.plain || '';
      const findingOwners = Array.isArray(finding.owners) && finding.owners.length
        ? finding.owners
        : (finding.path ? window.EvoGitHubEnterprise?.ownersForPath?.(env.graph, finding.path) : []);
      append(head,
        el('span', 'badge ' + (rule.severity === 'ERROR' ? 'bad' : 'warn'), `${rule.severity || 'FINDING'} · ${clsStr}`),
        el('strong', 'rs-finding-name', rule.name || rule.id || finding.kind || 'Policy finding'),
        window.EvoGitHubEnterprise?.makeOwnerPills?.(findingOwners)
      );
      box.append(head);
      if (scopePlain) box.append(el('p', 'small muted', scopePlain));

      // Dependency path with resolved class labels (never raw sha256!)
      if (Array.isArray(bundle.path) && bundle.path.length) {
        const pathWrap = el('div', 'rs-path-wrap');
        pathWrap.append(el('span', 'small muted', finding.kind === 'ACYCLIC' ? 'Dependency cycle:' : 'Violation path:'));
        const chips = el('div', 'rs-path-chips');
        const renderPathChips = () => {
          chips.replaceChildren();
          bundle.path.forEach((id, idx) => {
            if (idx > 0) chips.append(el('span', 'rs-path-arrow', '→'));
            const resolved = window.EvoApp?.state?.labels?.get(id);
            const display = resolved || M.shortDigest(id);
            const chip = btn(display, () => {
              if (window.EvoApp?.state) {
                window.EvoApp.state.graphFocus = { seed: id, scope: bundle.request?.scope || 'TYPE', context: bundle.request?.context || env.context };
                window.EvoApp.loadTab('graph');
              }
            }, 'rs-path-chip');
            chip.title = resolved ? `${resolved} (${id})` : id;
            chips.append(chip);
          });
        };
        renderPathChips();
        if (typeof resolveLabels === 'function') {
          resolveLabels(bundle.path, env).then(() => { if (!env.signal.aborted) renderPathChips(); }).catch(() => {});
        }
        pathWrap.append(chips);
        box.append(pathWrap);
      }

      const diagOut = el('div', 'rs-diag-output');
      const actions = el('div', 'rs-action-row');
      const runDiagBtn = btn(studio.diagnosis ? 'Re-run Diagnosis' : '1. Run Evidence-Grounded Diagnosis', async () => {
        runDiagBtn.disabled = true;
        diagOut.replaceChildren(el('p', 'small muted', 'Running grounded diagnosis and verifying source citations…'));
        try {
          const args = { identity: item.identity, bundle: item.bundleIdentity, context: env.context };
          studio.diagnosis = await api(`/graphs/${env.graph}/reports/${reportId}/diagnosis`, { arguments: args }, env.signal, 'POST', 90000);
          renderAll();
        } catch (err) {
          if (!env.signal.aborted) diagOut.replaceChildren(el('p', 'form-error', err.message || 'Diagnosis failed.'));
        } finally {
          runDiagBtn.disabled = false;
        }
      }, 'button primary');

      append(actions,
        runDiagBtn,
        btn('Inspect Raw Evidence', () => inspectFinding(item, reportId, env), 'button subtle'),
        capabilities?.architectureChatEnabled
          ? btn('Ask AI Chat about Finding', () => askAboutFinding(item, reportId, env), 'button subtle')
          : null,
        (isGitHubRepo && capabilities?.githubEnterpriseEnabled && window.EvoGitHubEnterprise?.openCreateIssueModal)
          ? btn('Create GitHub Issue', () => window.EvoGitHubEnterprise.openCreateIssueModal(setupId || env.setup, {
              graph: env.graph,
              report: reportId,
              findingId: item.identity,
              title: rule.name || rule.id || finding.kind || 'Architecture finding',
              filePath: studio.diagnosis?.citations?.[0]?.path || finding.path || ''
            }), 'button subtle')
          : null
      );
      box.append(actions, diagOut);

      if (studio.diagnosis) {
        const d = studio.diagnosis;
        const ok = d.status === 'CITATIONS_VALIDATED';
        const banner = el('div', 'rs-inline-notice ' + (ok ? 'is-good' : 'is-warn'));
        banner.append(
          el('strong', '', `${d.mode === 'LOCAL_REPLAY' ? 'Deterministic Local Replay' : 'Live AI Diagnosis'} · ${ok ? 'Citations Validated' : 'Withheld'}`),
          el('p', 'small', d.explanation || d.reason || 'Review evidence details below.')
        );
        diagOut.append(banner);

        if (Array.isArray(d.citations) && d.citations.length) {
          const cList = el('div', 'rs-citations-list');
          cList.append(el('span', 'small muted', `Cited source coordinates (${d.citations.length}):`));
          for (const c of d.citations) {
            const sp = c.span || {};
            const label = `${c.path} · L${sp.startLine || 1}:${sp.startColumn || 1}–L${sp.endLine || sp.startLine || 1}`;
            const rowWrap = el('div', 'gh-owner-row');
            rowWrap.append(btn(label, () => openCapturedSource(env.graph, c.path, Math.max(1, (sp.startLine || 1) - 10), (sp.endLine || sp.startLine || 1) + 40, sp.startLine || 1), 'rs-citation-btn'));
            const ghEnt = window.EvoGitHubEnterprise;
            const permUrl = ghEnt?.permalinkFor?.(c.path, sp.startLine || 1, sp.endLine || sp.startLine || 1, c.permalink, setupId || env.setup);
            const permA = ghEnt?.makePermalinkAnchor?.(permUrl, 'View on GitHub ↗', 'gh-permalink-link');
            if (permA) rowWrap.append(permA);
            const cOwners = Array.isArray(c.owners) && c.owners.length ? c.owners : ghEnt?.ownersForPath?.(env.graph, c.path);
            const ownerPills = ghEnt?.makeOwnerPills?.(cOwners);
            if (ownerPills) rowWrap.append(ownerPills);
            cList.append(rowWrap);
          }
          diagOut.append(cList);
        }

        if (ok) {
          diagOut.append(append(el('div', 'rs-action-row'),
            btn('Continue to Step 2: Code Patch →', () => {
              studio.step = 2;
              renderAll();
            }, 'button primary')));
        }
      }

      stageHost.replaceChildren(box);
    }

    /* ── STEP 2: Patch Generation + Multi-File Unified/Side-by-Side Diff ──── */
    async function loadOptionsIfNeeded(statusEl) {
      if (studio.options || studio.mode === 'adhoc' || !studio.selectedFinding || !studio.diagnosis?.input) return;
      if (statusEl) statusEl.textContent = 'Checking applicable recipes and AI repair configuration…';
      try {
        const args = {
          identity: studio.selectedFinding.identity,
          bundle: studio.selectedFinding.bundleIdentity,
          context: env.context,
          diagnosisInput: studio.diagnosis.input
        };
        studio.options = await api(`/graphs/${env.graph}/reports/${reportId}/repair-options`, { arguments: args }, env.signal, 'POST', 30000);
      } catch (err) {
        if (statusEl && !env.signal.aborted) statusEl.textContent = err.message || 'Repair options unavailable.';
      }
    }

    function renderStep2() {
      const box = el('div', 'rs-card');
      const statusLine = el('p', 'small muted');
      statusLine.setAttribute('role', 'status');

      const renderContextBadges = (leidenList, advisoryList) => {
        const lArr = Array.isArray(leidenList) ? leidenList : [];
        const aArr = Array.isArray(advisoryList) ? advisoryList : [];
        if (!lArr.length && !aArr.length) return null;
        const wrap = el('div', 'rs-verdict-badges rs-intel-badges');
        for (const c of lArr.slice(0, 4)) {
          const lbl = c.communityLabel || c.communityId || 'Community';
          const pill = el('span', 'badge ' + (c.misplaced ? 'warn' : ''),
            `Leiden: ${lbl}` + (c.misplaced ? ' · Misplaced' : '') + (c.bridgeCount ? ` · ${c.bridgeCount} bridges` : ''));
          pill.title = `Dominant package: ${c.dominantPackage || '—'} (${c.memberCount ?? 0} members)`;
          wrap.append(pill);
        }
        for (const a of aArr.slice(0, 4)) {
          const isSql = String(a.kind || '').includes('SQL');
          const prefix = isSql ? 'SQL DDL' : 'ADR/Doc';
          const pill = el('span', 'badge evo-adv-badge', `${prefix}: ${a.label || a.sourcePath}`);
          pill.title = `${a.sourcePath}:${a.startLine || 1}–${a.endLine || 1} (${a.matchBasis || 'advisory'} · ${a.trust || 'ADVISORY_UNVERIFIED'})`;
          wrap.append(pill);
        }
        return wrap;
      };

      if (studio.mode === 'finding' && studio.selectedFinding && studio.diagnosis?.input) {
        const controlsBox = el('div', 'rs-patch-controls');
        box.append(el('h4', '', '2. Generate Compiler-Checked Code Patch'), controlsBox, statusLine);

        const renderControls = () => {
          controlsBox.replaceChildren();
          const opts = studio.options || { recipes: [], applicableRecipes: [] };
          const ctxBadges = renderContextBadges(opts.leidenContext, opts.advisoryContext);
          if (ctxBadges) controlsBox.append(ctxBadges);
          const ranked = M.rankApplicableRecipes
            ? M.rankApplicableRecipes(opts.recipes || [], opts.applicableRecipes || [])
            : (opts.recipes || []);

          if (ranked.length) {
            const row = el('div', 'rs-action-row');
            const sel = el('select', 'rs-select');
            sel.setAttribute('aria-label', 'Select repair recipe');
            for (const r of ranked) {
              const opt = new Option((r.applicable ? '★ [Applicable] ' : '') + (r.title || r.id), r.id);
              sel.append(opt);
            }
            const firstApp = ranked.find(r => r.applicable);
            if (firstApp) sel.value = firstApp.id;
            const runRecipe = btn('Generate Recipe Patch', () => generateProposal({ strategy: 'RECIPE', recipe: sel.value }), 'button primary');
            row.append(sel, runRecipe);
            controlsBox.append(row);
          }

          if (opts.ai?.input) {
            const aiPanel = el('div', 'rs-ai-disclosure');
            const fileList = (opts.ai.files || []).map(f => f.path).join(', ');
            const check = el('input');
            check.type = 'checkbox';
            const aiBtn = btn(`Propose with AI (${opts.ai.provider?.model || 'configured'})`, () => generateProposal({
              strategy: 'AI',
              modelInput: opts.ai.input,
              maxRepairAttempts: 3
            }), 'button primary');
            aiBtn.disabled = true;
            check.addEventListener('change', () => { aiBtn.disabled = !check.checked; });
            aiPanel.append(
              el('p', 'small', `AI repair sends captured source (${fileList || 'cited files'}) to ${opts.ai.provider?.endpoint || 'configured provider'} and checks syntax with javac/parser.`),
              append(el('label', 'gh-confirm-check'), check, el('span', '', 'Allow captured source disclosure for AI repair')),
              aiBtn
            );
            controlsBox.append(aiPanel);
          }
        };

        const generateProposal = async extra => {
          statusLine.textContent = 'Generating patch and validating syntax…';
          try {
            const args = {
              identity: studio.selectedFinding.identity,
              bundle: studio.selectedFinding.bundleIdentity,
              context: env.context,
              diagnosisInput: studio.diagnosis.input,
              ...extra
            };
            studio.proposal = await api(`/graphs/${env.graph}/reports/${reportId}/repair-proposal`, { arguments: args }, env.signal, 'POST', 120000);
            studio.verifyReport = null;
            studio.applyPreview = null;
            studio.applyReceipt = null;
            studio.prPlan = null;
            studio.prResult = null;
            studio.unverifiedAck = false;
            renderAll();
          } catch (err) {
            if (!env.signal.aborted) statusLine.textContent = err.message || 'Could not generate proposal.';
          }
        };

        if (!studio.options) {
          loadOptionsIfNeeded(statusLine).then(() => {
            if (!env.signal.aborted) {
              statusLine.textContent = '';
              renderControls();
            }
          });
        } else {
          renderControls();
        }
      } else {
        box.append(el('h4', '', '2. Proposed Multi-File Patch'), statusLine);
      }

      if (studio.proposal) {
        if (studio.proposal.status === 'PROPOSED') {
          const cand = studio.proposal.proposal?.candidate || {};
          const patch = cand.patch || '';
          const fallbackPath = cand.edits?.[0]?.path || cand.edit?.path || 'repair.patch';
          const attempts = Number(studio.proposal.attempts ?? cand.attempts ?? 1);

          const badges = el('div', 'rs-verdict-badges');
          append(badges,
            el('span', 'badge good', 'Compiler Syntax PASSED'),
            attempts > 1 ? el('span', 'badge good', `AI Self-Healed · ${attempts} attempts`) : null,
            el('span', 'badge warn', 'Unverified candidate'),
            cand.strategy ? el('span', 'badge', cand.strategy) : null
          );
          box.append(badges);

          const propCtxBadges = renderContextBadges(studio.proposal.leidenContext, studio.proposal.advisoryContext);
          if (propCtxBadges) box.append(propCtxBadges);

          if (cand.plan?.summary || cand.recipe?.summary) {
            box.append(el('p', 'small', cand.plan?.summary || cand.recipe?.summary));
          }

          if (Array.isArray(studio.proposal.toolTrace) && studio.proposal.toolTrace.length) {
            const traceDet = el('details', 'rs-log-details');
            traceDet.append(el('summary', '', `Read-only repair agent tool calls (${studio.proposal.toolTrace.length})`));
            const traceUl = el('ul', 'rs-tool-trace-list');
            for (const tr of studio.proposal.toolTrace) {
              traceUl.append(el('li', 'small mono', `${tr.tool || 'tool'}: ${tr.summary || ''}`));
            }
            traceDet.append(traceUl);
            box.append(traceDet);
          }

          box.append(renderMultiFileDiff(patch, fallbackPath, 'unified'));

          const actions = el('div', 'rs-action-row');
          const copyBtn = btn('Copy Patch', async () => {
            try {
              await navigator.clipboard?.writeText(patch);
              window.EvoCopyFx?.flash?.(copyBtn, 'Copied!', 'Copy Patch');
            } catch (_) {}
          }, 'button subtle');
          const dlBtn = btn('Download .patch', () => downloadBlob(new Blob([patch], { type: 'text/x-diff' }), 'repair.patch'), 'button subtle');
          const verifyCta = btn('3. Verify in Isolated Sandbox →', () => {
            studio.step = 3;
            renderAll();
            void startSandboxVerification();
          }, 'button primary');
          const skipToGate = btn('Inspect Confirmation Gate (Unverified)', () => {
            studio.step = 5;
            renderAll();
          }, 'button subtle');
          actions.append(copyBtn, dlBtn, verifyCta, skipToGate);
          box.append(actions);
        } else {
          box.append(el('div', 'rs-inline-notice is-warn',
            `Proposal withheld: ${studio.proposal.reason || 'Select an applicable recipe or enable AI repair.'}`));
        }
      }

      stageHost.replaceChildren(box);
    }

    /* ── STEP 3: Async Isolated Sandbox Verification (SSE + Fallback) ─────── */
    function unwrapJobReport(payload) {
      if (!payload || typeof payload !== 'object') return payload;
      // Backend serializeRepairJob promotes UI-aligned aliases (scoreV2Delta, violationsDelta, build, tests, behavioralDiff, springPreservation, rounds) onto top-level
      if (payload.verdict && (payload.scoreV2Delta || payload.build || payload.behavioralDiff || !payload.report)) {
        return payload;
      }
      if (payload.report && typeof payload.report === 'object') {
        return {
          ...payload.report,
          jobId: payload.jobId || payload.report.jobId,
          rounds: Array.isArray(payload.report.rounds) && payload.report.rounds.length
            ? payload.report.rounds
            : (Array.isArray(payload.rounds) ? payload.rounds : [])
        };
      }
      return payload;
    }

    async function startSandboxVerification() {
      stopVerifyStream();
      const propId = studio.proposal?.proposal?.identity;
      if (!propId) return;
      studio.liveRounds = [];
      studio.applyPreview = null;
      studio.applyPreviewProposalId = null;
      studio.applyReceipt = null;
      studio.prPlan = null;
      studio.prResult = null;

      const stageBody = stageHost.querySelector('.rs-verify-live') || stageHost;
      const logPre = stageBody.querySelector('.rs-log-pre');
      const statusEl = stageBody.querySelector('.rs-verify-status');
      const progressFill = stageBody.querySelector('.rs-verify-fill');
      const timelineHost = stageBody.querySelector('.rs-live-rounds');
      const setStatus = (msg, pct) => {
        if (statusEl) statusEl.textContent = msg;
        if (progressFill && pct != null) progressFill.style.width = `${Math.max(4, Math.min(100, pct))}%`;
      };
      const appendLog = line => {
        if (!logPre || !line) return;
        logPre.textContent += (logPre.textContent ? '\n' : '') + line;
        logPre.scrollTop = logPre.scrollHeight;
      };
      const recordLiveEvent = evData => {
        if (!evData || !timelineHost) return;
        const stageName = String(evData.stage || '').toUpperCase();
        const details = evData.detail || evData.details || evData;
        const roundNum = Number(details.round ?? evData.round ?? 0);
        const msg = evData.message || stageName;
        if (!stageName && !msg) return;
        studio.liveRounds.push({ round: roundNum || null, stage: stageName, message: msg });
        timelineHost.hidden = false;
        const li = el('li', 'rs-round-item');
        li.append(
          append(el('div', 'rs-verdict-badges'),
            roundNum > 0 ? el('span', 'badge', `Round ${roundNum}`) : null,
            stageName ? el('span', 'badge ' + (stageName === 'FAILED' || stageName === 'INTERRUPTED_ON_RESTART' ? 'bad' : stageName === 'REPORT_READY' ? 'good' : ''), stageName) : null),
          el('p', 'small muted', msg)
        );
        timelineHost.append(li);
      };

      setStatus('Submitting isolated sandbox verification job…', 10);
      try {
        let jobResp;
        try {
          const verifyBody = {
            graph: env.graph,
            context: env.context,
            runIsolated: true,
            closedLoop: Boolean(studio.closedLoopEnabled),
            maxRounds: studio.closedLoopEnabled ? studio.maxRounds : 1,
            runBehavioralDiff: Boolean(studio.runBehavioralDiff),
            proposeCharacterizationTests: Boolean(studio.runBehavioralDiff && studio.proposeCharacterizationTests)
          };
          if (reportId) verifyBody.report = reportId;
          if (studio.selectedFinding?.identity) verifyBody.finding = studio.selectedFinding.identity;
          if (studio.selectedFinding?.bundleIdentity) verifyBody.bundle = studio.selectedFinding.bundleIdentity;
          if (studio.diagnosis?.input) verifyBody.diagnosisInput = studio.diagnosis.input;
          jobResp = await api(`/repairs/${encodeURIComponent(propId)}/verify`, verifyBody, env.signal, 'POST', 30000);
        } catch (err) {
          if (err.status === 404 && reportId) {
            jobResp = await api(`/graphs/${env.graph}/reports/${reportId}/repair-verification/jobs`, {
              arguments: {
                identity: studio.selectedFinding?.identity,
                bundle: studio.selectedFinding?.bundleIdentity,
                context: env.context,
                diagnosisInput: studio.diagnosis?.input,
                proposal: propId,
                runIsolated: true,
                closedLoop: Boolean(studio.closedLoopEnabled),
                maxRounds: studio.closedLoopEnabled ? studio.maxRounds : 1,
                runBehavioralDiff: Boolean(studio.runBehavioralDiff),
                proposeCharacterizationTests: Boolean(studio.runBehavioralDiff && studio.proposeCharacterizationTests)
              }
            }, env.signal, 'POST', 30000);
          } else {
            throw err;
          }
        }

        studio.verifyJob = jobResp;
        if (jobResp?.verdict && !jobResp?.jobId) {
          studio.verifyReport = unwrapJobReport(jobResp);
          studio.step = 4;
          renderAll();
          return;
        }

        const jobId = jobResp.jobId || jobResp.id;
        const eventsUrl = jobResp.eventsUrl || `/api/v1/repairs/jobs/${encodeURIComponent(jobId)}/events`;

        const finishFromJob = async () => {
          stopVerifyStream();
          const finalJob = await api(`/repairs/jobs/${encodeURIComponent(jobId)}`, undefined, env.signal, 'GET', 15000);
          if (env.signal.aborted) return;
          studio.verifyJob = finalJob;
          if (String(finalJob?.stage || '').toUpperCase() === 'INTERRUPTED_ON_RESTART' && !finalJob?.report && !finalJob?.verdict) {
            setStatus('Verification job was interrupted by server restart (persisted in jobs.db). Re-run when ready.', 100);
            return;
          }
          studio.verifyReport = unwrapJobReport(finalJob);
          studio.step = 4;
          renderAll();
        };

        const pollFallback = async () => {
          for (let i = 0; i < 60 && !env.signal.aborted; i++) {
            await new Promise(r => setTimeout(r, 500));
            const cur = await api(`/repairs/jobs/${encodeURIComponent(jobId)}`, undefined, env.signal, 'GET', 12000);
            if (env.signal.aborted) return;
            const st = String(cur.stage || cur.status || 'RUNNING').toUpperCase();
            setStatus(`Sandbox stage: ${st}`, cur.progress != null ? Math.round(cur.progress * 100) : Math.min(92, 20 + i * 10));
            if (cur.logTail) appendLog(Array.isArray(cur.logTail) ? cur.logTail.join('\n') : String(cur.logTail));
            if (st === 'INTERRUPTED_ON_RESTART' && !cur.report && !cur.verdict) {
              setStatus('Verification job was interrupted by server restart (persisted in jobs.db).', 100);
              return;
            }
            if (cur.terminal || ['SUCCEEDED', 'COMPLETED', 'REPORT_READY', 'FAILED', 'REJECTED', 'CANCELLED', 'FINISHED'].includes(st) || cur.verdict || cur.report) {
              studio.verifyJob = cur;
              studio.verifyReport = unwrapJobReport(cur);
              studio.step = 4;
              renderAll();
              return;
            }
          }
        };

        if (typeof EventSource === 'function') {
          const es = new EventSource(eventsUrl);
          studio.verifySse = es;
          let handledTerminal = false;
          const parse = ev => { try { return ev.data ? JSON.parse(ev.data) : null; } catch (_) { return null; } };
          es.addEventListener('stage', ev => {
            const d = parse(ev);
            if (!d) return;
            recordLiveEvent(d);
            setStatus(d.message || `Stage: ${d.stage}`, d.percent ?? (d.progress != null ? Math.round(d.progress * 100) : 45));
            const st = String(d.stage || '').toUpperCase();
            if (st === 'REPORT_READY' || st === 'FAILED' || st === 'CANCELLED' || st === 'INTERRUPTED_ON_RESTART') {
              if (!handledTerminal) {
                handledTerminal = true;
                void finishFromJob();
              }
            }
          });
          es.addEventListener('log', ev => {
            const d = parse(ev);
            if (d?.line || d?.text) appendLog(d.line || d.text);
          });
          es.addEventListener('progress', ev => {
            const d = parse(ev);
            if (!d) return;
            recordLiveEvent(d);
            setStatus(d.message || d.stage || 'Running isolated checks…', d.percent ?? (d.progress != null ? Math.round(d.progress * 100) : 65));
          });
          const onDone = ev => {
            if (handledTerminal) return;
            handledTerminal = true;
            const d = parse(ev);
            if (d && (d.verdict || d.report)) {
              stopVerifyStream();
              studio.verifyReport = unwrapJobReport(d);
              studio.step = 4;
              renderAll();
            } else {
              void finishFromJob();
            }
          };
          es.addEventListener('completed', onDone);
          es.addEventListener('complete', onDone);
          es.addEventListener('failed', onDone);
          es.onerror = () => {
            if (handledTerminal) return;
            stopVerifyStream();
            void pollFallback();
          };
        } else {
          await pollFallback();
        }
      } catch (err) {
        if (!env.signal.aborted) {
          setStatus(`Verification failed: ${err.message}`, 100);
        }
      }
    }

    function renderStep3() {
      const box = el('div', 'rs-card rs-verify-live');
      const backendMode = studio.sandboxSetup?.configured
        ? studio.sandboxSetup.isolationMode
        : (capabilities?.repairSandboxMode || (capabilities?.repairIsolatedRunnerConfigured ? 'ISOLATED_SANDBOX' : 'PASSIVE_REANALYSIS_ONLY'));
      box.append(
        append(el('div', 'rs-verdict-badges'),
          el('h4', '', '3. Isolated Sandbox Verification'),
          el('span', 'badge', `Mode: ${backendMode}`)),
        el('p', 'small muted', 'Applies the candidate patch to a disposable isolated copy — never mutating your working tree or running untrusted builds on the host.')
      );

      // Moonshot #1 Closed-Loop & 3-State Behavioral Diff options
      const optBox = el('div', 'rs-verify-options');
      const clCheck = el('input');
      clCheck.type = 'checkbox';
      clCheck.checked = studio.closedLoopEnabled;
      clCheck.addEventListener('change', () => { studio.closedLoopEnabled = clCheck.checked; });

      const bdCheck = el('input');
      bdCheck.type = 'checkbox';
      bdCheck.checked = studio.runBehavioralDiff;
      bdCheck.addEventListener('change', () => {
        studio.runBehavioralDiff = bdCheck.checked;
        probeCheck.disabled = !bdCheck.checked;
      });

      const probeCheck = el('input');
      probeCheck.type = 'checkbox';
      probeCheck.checked = studio.proposeCharacterizationTests;
      probeCheck.disabled = !studio.runBehavioralDiff;
      probeCheck.addEventListener('change', () => { studio.proposeCharacterizationTests = probeCheck.checked; });

      append(optBox,
        append(el('label', 'gh-confirm-check'), clCheck, el('span', '', 'Closed-loop self-healing (up to 3 sandbox-guided rounds)')),
        append(el('label', 'gh-confirm-check'), bdCheck, el('span', '', 'Run 3-state behavioral diff (baseline vs candidate in sandbox)')),
        append(el('label', 'gh-confirm-check'), probeCheck, el('span', '', 'Synthesize syntax-gated characterization probes (optional)'))
      );

      const track = el('div', 'rs-verify-track');
      const fill = el('div', 'rs-verify-fill');
      const jobStage = String(studio.verifyJob?.stage || studio.verifyJob?.status || '').toUpperCase();
      const isTerminalJob = Boolean(studio.verifyReport || studio.verifyJob?.terminal
        || ['REPORT_READY', 'FAILED', 'CANCELLED', 'INTERRUPTED_ON_RESTART'].includes(jobStage));
      fill.style.width = isTerminalJob ? '100%' : '18%';
      track.append(fill);
      let initialStatus = 'Ready to launch isolated verification.';
      if (studio.verifyReport) {
        initialStatus = 'Verification complete.';
      } else if (jobStage === 'INTERRUPTED_ON_RESTART') {
        initialStatus = `INTERRUPTED_ON_RESTART: ${studio.verifyJob?.error || 'Verification job was interrupted by server restart (persisted in jobs.db). Re-run when ready.'}`;
      } else if (jobStage === 'FAILED') {
        initialStatus = studio.verifyJob?.error ? `Verification failed: ${studio.verifyJob.error}` : 'Verification job failed. Inspect events below or re-run.';
      } else if (jobStage === 'CANCELLED') {
        initialStatus = 'Verification job was cancelled by operator.';
      }
      const statusEl = el('p', 'rs-verify-status small', initialStatus);
      statusEl.setAttribute('role', 'status');
      const liveRoundsList = el('ol', 'rs-round-timeline rs-live-rounds');
      liveRoundsList.hidden = !studio.liveRounds.length;
      for (const item of studio.liveRounds) {
        const li = el('li', 'rs-round-item');
        const st = String(item.stage || '').toUpperCase();
        li.append(
          append(el('div', 'rs-verdict-badges'),
            item.round > 0 ? el('span', 'badge', `Round ${item.round}`) : null,
            st ? el('span', 'badge ' + (st === 'FAILED' || st === 'INTERRUPTED_ON_RESTART' ? 'bad' : st === 'REPORT_READY' ? 'good' : ''), st) : null),
          el('p', 'small muted', item.message || st)
        );
        liveRoundsList.append(li);
      }
      const logPre = el('pre', 'rs-log-pre', '');
      const actions = el('div', 'rs-action-row');
      append(actions,
        btn(studio.verifyReport ? 'Re-run Sandbox Verification' : 'Run Isolated Sandbox Verification', () => void startSandboxVerification(), 'button primary'),
        btn('Configure Sandbox…', () => { if (!studio.sandboxOpen) sandboxToggleBtn.click(); }, 'button subtle'),
        studio.verifyReport ? btn('View Impact Report →', () => { studio.step = 4; renderAll(); }, 'button subtle') : null
      );
      box.append(optBox, track, statusEl, liveRoundsList, logPre, actions);
      stageHost.replaceChildren(box);
    }

    /* ── STEP 4: Verification & Impact Report Card ────────────────────────── */
    function renderStep4() {
      if (!studio.verifyReport) {
        const emptyCard = el('div', 'rs-card');
        emptyCard.append(
          el('h4', '', 'No Verification Report Yet'),
          el('p', 'small muted', 'Run isolated sandbox verification in Step 3 to measure build/test status, Health Score v2 delta, violation transitions, and capability gap impact.'),
          btn('Go to Step 3: Verify in Sandbox', () => { studio.step = 3; renderAll(); }, 'button primary')
        );
        stageHost.replaceChildren(emptyCard);
        return;
      }
      const jobId = studio.verifyJob?.jobId || studio.verifyReport?.jobId;
      const reportCard = renderVerificationReportCard(studio.verifyReport, {
        onDownloadMarkdown: async () => {
          try {
            let md = studio.verifyReport.markdownReport || '';
            if (!md && jobId) {
              const resp = await fetch(`/api/v1/repairs/jobs/${encodeURIComponent(jobId)}/report.md`, { credentials: 'same-origin', signal: env.signal });
              if (resp.ok) md = await resp.text();
            }
            if (!md) {
              md = `# Repair Verification Report\n\n- Verdict: ${studio.verifyReport.verdict}\n- Trust Label: ${studio.verifyReport.trustLabel || 'UNSPECIFIED'}\n`;
            }
            downloadBlob(new Blob([md], { type: 'text/markdown;charset=utf-8' }), 'repair-verification-report.md');
          } catch (_) {}
        },
        onProceedToApply: () => {
          studio.step = 5;
          renderAll();
        }
      });
      stageHost.replaceChildren(reportCard);
    }

    /* ── STEP 5: Explicit Confirmation Gate (Local Apply, Rollback & GitHub PR) ── */
    function activeProposalId() {
      return studio.verifyJob?.finalProposalId
        || studio.verifyReport?.provenance?.proposalIdentity
        || studio.proposal?.proposal?.identity
        || null;
    }

    async function loadApplyPreview() {
      const propId = activeProposalId();
      if (!propId) return null;
      return api(`/repairs/${encodeURIComponent(propId)}/apply/preview`, {
        graph: env.graph,
        setup: setupId || env.setup || null,
        verificationJobId: studio.verifyJob?.jobId || studio.verifyReport?.jobId || null
      }, env.signal, 'POST', 20000);
    }

    function renderStep5() {
      const box = el('div', 'rs-card rs-gate-card');
      const propId = activeProposalId();
      if (!propId) {
        box.append(el('p', 'small muted', 'Generate a patch in Step 2 before opening the confirmation gate.'));
        stageHost.replaceChildren(box);
        return;
      }

      const trust = M.repairTrustMeta(
        studio.applyPreview?.verdict || studio.verifyReport?.verdict || 'UNVERIFIED',
        studio.applyPreview?.trustLabel || studio.verifyReport?.trustLabel || 'UNVERIFIED_CANDIDATE'
      );

      box.append(
        append(el('div', 'rs-verdict-badges'),
          el('h4', '', '5. Explicit Confirmation Gate'),
          el('span', 'badge ' + (trust.verdictTone === 'good' ? 'good' : trust.verdictTone === 'bad' ? 'bad' : 'warn'), trust.verdictShort),
          el('span', 'rs-trust-pill is-' + trust.trustTone, trust.trustTitle)),
        el('p', 'small muted', 'No local files are written and nothing is pushed to GitHub until you explicitly review and confirm below.')
      );

      const previewBox = el('div', 'rs-gate-preview');
      const gateBody = el('div', 'rs-gate-body');
      box.append(previewBox, gateBody);
      stageHost.replaceChildren(box);

      const renderGateControls = preview => {
        previewBox.replaceChildren();
        gateBody.replaceChildren();

        // Preview safety status bar
        const statusGrid = el('div', 'rs-preview-meta');
        append(statusGrid,
          el('span', 'badge ' + (preview?.beforeHashesValid === false ? 'bad' : 'good'),
            preview?.beforeHashesValid === false ? 'Before-Hash DRIFT' : 'Before-Hashes Verified'),
          preview?.currentBranch ? el('span', 'badge', 'Current branch: ' + preview.currentBranch) : null,
          preview?.gitStatus ? el('span', 'badge', 'Git tree: ' + preview.gitStatus) : null,
          preview?.expiresAt ? el('span', 'small muted', 'Approval token valid until ' + new Date(preview.expiresAt).toLocaleTimeString()) : null
        );
        previewBox.append(statusGrid);

        // If already applied -> show receipt + Rollback button
        if (studio.applyReceipt) {
          const rec = studio.applyReceipt;
          const recCard = el('div', 'rs-inline-notice is-good');
          recCard.append(
            el('strong', '', `Patch Applied (${rec.mode || 'LOCAL'})`),
            el('p', 'small', [
              rec.branchName ? `Branch: ${rec.branchName}` : null,
              rec.commitSha ? `Commit: ${rec.commitSha.slice(0, 10)}` : null,
              rec.worktreePath ? `Worktree: ${rec.worktreePath}` : null
            ].filter(Boolean).join(' · ') || 'Files updated cleanly.'),
            el('p', 'small mono muted', `Apply ID: ${rec.applyId || rec.id || 'recorded'}`)
          );
          const rbStatus = el('p', 'small muted');
          rbStatus.setAttribute('role', 'status');
          if (rec.applyId || rec.id) {
            const rbBtn = btn('Rollback Applied Patch', async () => {
              rbBtn.disabled = true;
              rbStatus.textContent = 'Restoring pre-apply backup snapshot…';
              try {
                await api(`/repairs/applies/${encodeURIComponent(rec.applyId || rec.id)}/rollback`, { confirmedByUser: true }, env.signal, 'POST', 20000);
                rbStatus.textContent = 'Rollback complete — original files restored.';
                window.EvoToast?.push?.({ kind: 'ok', title: 'Applied patch rolled back cleanly' });
              } catch (err) {
                rbStatus.textContent = err.message || 'Rollback failed.';
                rbBtn.disabled = false;
              }
            }, 'button danger');
            recCard.append(rbBtn, rbStatus);
          }
          gateBody.append(recCard);
        }

        // Target Mode Selector: Option A (New Branch), Option B (Direct Local), Option C (GitHub PR)
        let targetMode = 'NEW_BRANCH_WORKTREE';
        const showPrOption = Boolean(isGitHubRepo || capabilities?.githubIntegrationEnabled);
        const modeRow = el('div', 'rs-target-modes');
        modeRow.setAttribute('role', 'radiogroup');
        modeRow.setAttribute('aria-label', 'Patch application mode');

        const modes = [
          ['NEW_BRANCH_WORKTREE', 'Option A · Apply to New Git Branch (Recommended)', 'Creates an isolated Git branch / worktree without disturbing your active checkout.'],
          ['DIRECT_WORKING_TREE', 'Option B · Apply Directly to Local Source', 'Modifies your current working tree with a recorded rollback backup receipt.'],
          ...(showPrOption ? [['GITHUB_PR', 'Option C · Push & Open Verified GitHub PR', 'Prepares an auditable PR plan and pushes only after separate remote confirmation.']] : [])
        ];

        const formHost = el('div', 'rs-gate-form');

        const renderModeForm = () => {
          for (const cardBtn of modeRow.children) {
            const active = cardBtn.dataset.mode === targetMode;
            cardBtn.classList.toggle('is-selected', active);
            cardBtn.setAttribute('aria-checked', String(active));
          }
          formHost.replaceChildren();

          const effectiveVerdict = preview?.verdict || studio.verifyReport?.verdict || 'UNVERIFIED';
          const baseEval = M.evaluateConfirmationGate(
            { ...(preview || {}), verdict: effectiveVerdict },
            { mode: targetMode, branchName: 'placeholder', prTitle: 'placeholder', userConfirmed: true, acknowledgeUnverified: true, remoteConfirmed: true }
          );

          if (baseEval.hardBlocked) {
            const hardAlert = el('div', 'rs-hard-block');
            hardAlert.setAttribute('role', 'alert');
            hardAlert.append(
              el('strong', '', 'Action Blocked by Safety Gate'),
              ...baseEval.blockers.map(msg => el('p', 'small', msg))
            );
            formHost.append(hardAlert);
            return;
          }

          if (targetMode === 'GITHUB_PR') {
            renderGitHubPrForm(formHost, preview, effectiveVerdict);
            return;
          }

          // Local Apply Form (Option A or Option B)
          const branchInput = el('input', 'rs-input');
          branchInput.value = preview?.suggestedBranch || `evo/repair-${String(propId).slice(-8).replace(/[^a-zA-Z0-9-]/g, '')}`;
          const commitInput = el('input', 'rs-input');
          commitInput.value = `fix(architecture): apply Evo repair proposal ${M.shortDigest(propId)}`;

          if (targetMode === 'NEW_BRANCH_WORKTREE') {
            formHost.append(
              append(el('label', 'rs-field'), el('span', 'small muted', 'New Git branch name'), branchInput),
              append(el('label', 'rs-field'), el('span', 'small muted', 'Commit message'), commitInput)
            );
          } else {
            formHost.append(el('div', 'rs-inline-notice is-warn',
              `Direct local edit on ${preview?.currentBranch || 'current checkout'}. A content-addressed backup will be saved so you can click Rollback immediately if needed.`));
          }

          const ackCheck = el('input');
          ackCheck.type = 'checkbox';
          if (baseEval.requiresUnverifiedAck) {
            formHost.append(append(el('label', 'gh-confirm-check rs-unverified-ack'), ackCheck,
              el('span', '', 'I understand this patch is UNVERIFIED (sandbox verification has not passed) and choose to proceed anyway.')));
          }

          const confirmCheck = el('input');
          confirmCheck.type = 'checkbox';
          formHost.append(append(el('label', 'gh-confirm-check'), confirmCheck,
            el('span', '', targetMode === 'NEW_BRANCH_WORKTREE'
              ? 'I have reviewed the diff and confirm applying this patch to the new local Git branch.'
              : 'I have reviewed the diff and confirm modifying files directly in my local working tree.')));

          const blockerHint = el('p', 'small muted');
          const execStatus = el('p', 'small muted');
          execStatus.setAttribute('role', 'status');
          const confirmBtn = btn(
            targetMode === 'NEW_BRANCH_WORKTREE' ? 'Confirm & Apply to New Branch' : 'Confirm & Apply Directly to Local Source',
            async () => {
              confirmBtn.disabled = true;
              execStatus.textContent = 'Applying patch with hash verification…';
              try {
                const confirmBody = {
                  approvalToken: preview?.approvalToken || 'preview-token',
                  userConfirmed: true,
                  mode: targetMode,
                  ...(env.graph ? { graph: env.graph } : {}),
                  ...((setupId || env.setup) ? { setup: setupId || env.setup } : {}),
                  ...((studio.verifyJob?.jobId || studio.verifyReport?.jobId) ? { verificationJobId: studio.verifyJob?.jobId || studio.verifyReport?.jobId } : {}),
                  ...(targetMode === 'NEW_BRANCH_WORKTREE' ? { branchName: branchInput.value.trim(), commitMessage: commitInput.value.trim() } : {}),
                  ...(baseEval.requiresUnverifiedAck ? { acknowledgeUnverified: ackCheck.checked } : {})
                };
                const receipt = await api(`/repairs/${encodeURIComponent(propId)}/apply/confirm`, confirmBody, env.signal, 'POST', 30000);
                studio.applyReceipt = receipt;
                window.EvoToast?.push?.({ kind: 'ok', title: 'Patch applied cleanly', detail: receipt?.branchName || targetMode });
                renderGateControls(preview);
              } catch (err) {
                execStatus.textContent = err.message || 'Apply failed.';
                syncValid();
              }
            },
            'button primary'
          );

          const syncValid = () => {
            const evalRes = M.evaluateConfirmationGate(
              { ...(preview || {}), verdict: effectiveVerdict },
              {
                mode: targetMode,
                branchName: branchInput.value,
                userConfirmed: confirmCheck.checked,
                acknowledgeUnverified: ackCheck.checked
              }
            );
            confirmBtn.disabled = !evalRes.allowed;
            blockerHint.textContent = evalRes.allowed ? 'Ready to apply.' : evalRes.blockers[0];
          };
          branchInput.addEventListener('input', syncValid);
          ackCheck.addEventListener('change', syncValid);
          confirmCheck.addEventListener('change', syncValid);
          syncValid();

          formHost.append(append(el('div', 'rs-action-row'), confirmBtn), blockerHint, execStatus);
        };

        for (const [modeId, label, desc] of modes) {
          const cardBtn = btn('', () => { targetMode = modeId; renderModeForm(); }, 'rs-mode-card');
          cardBtn.dataset.mode = modeId;
          cardBtn.setAttribute('role', 'radio');
          cardBtn.append(el('strong', '', label), el('p', 'small muted', desc));
          modeRow.append(cardBtn);
        }

        gateBody.append(modeRow, formHost);
        renderModeForm();
      };

      function renderGitHubPrForm(host, preview, effectiveVerdict) {
        if (studio.prResult) {
          const done = el('div', 'rs-inline-notice is-good');
          done.append(
            el('strong', '', `Verified GitHub PR Opened${studio.prResult.number ? ' · #' + studio.prResult.number : ''}`),
            el('p', 'small', `Branch ${studio.prResult.headBranch || studio.prPlan?.headBranch || ''} pushed and PR created.`)
          );
          if (studio.prResult.url && (M?.isSafeHttpUrl ? M.isSafeHttpUrl(studio.prResult.url) : /^https?:\/\//i.test(String(studio.prResult.url)))) {
            const a = el('a', 'button primary', 'Open Pull Request on GitHub ↗');
            a.href = studio.prResult.url;
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
            done.append(a);
          }
          host.append(done);
          return;
        }

        const planStatus = el('p', 'small muted');
        planStatus.setAttribute('role', 'status');
        const isUnverified = !['VERIFIED', 'VERIFIED_DEMO', 'VERIFIED_LOCAL_BUILD'].includes(String(effectiveVerdict).toUpperCase());

        if (!studio.prPlan) {
          const preAckCheck = el('input');
          preAckCheck.type = 'checkbox';
          preAckCheck.checked = Boolean(studio.unverifiedAck);
          const prepBtn = btn('Prepare GitHub PR Plan', async () => {
            prepBtn.disabled = true;
            planStatus.textContent = 'Preparing auditable GitHub PR plan (no push yet)…';
            try {
              studio.prPlan = await api('/github/repairs/prepare', {
                setup: setupId || env.setup || null,
                proposalId: propId,
                verificationJobId: studio.verifyJob?.jobId || studio.verifyReport?.jobId || null,
                allowUnverified: Boolean(studio.unverifiedAck)
              }, env.signal, 'POST', 20000);
              renderGitHubPrForm(host, preview, effectiveVerdict);
            } catch (err) {
              planStatus.textContent = err.message || 'Could not prepare PR plan.';
              prepBtn.disabled = isUnverified && !studio.unverifiedAck;
            }
          }, 'button primary');
          if (isUnverified) {
            prepBtn.disabled = !studio.unverifiedAck;
            preAckCheck.addEventListener('change', () => {
              studio.unverifiedAck = preAckCheck.checked;
              prepBtn.disabled = !studio.unverifiedAck;
            });
          }
          host.replaceChildren(...[
            el('p', 'small muted', 'Step 1 of 2: Generate the exact PR branch, title, diff stats, and trust banner before confirming any remote push.'),
            isUnverified ? append(el('label', 'gh-confirm-check rs-unverified-ack'), preAckCheck,
              el('span', '', 'Allow preparing a Draft Pull Request plan for an UNVERIFIED candidate patch')) : null,
            prepBtn,
            planStatus
          ].filter(Boolean));
          return;
        }

        const plan = studio.prPlan;
        host.replaceChildren();
        if (plan.trustBanner) {
          host.append(el('div', 'rs-inline-notice ' + (effectiveVerdict === 'VERIFIED' || effectiveVerdict === 'VERIFIED_DEMO' ? 'is-good' : 'is-warn'), plan.trustBanner));
        }
        const meta = el('div', 'rs-preview-meta');
        append(meta,
          el('span', 'badge', `Base: ${plan.baseBranch || 'main'}`),
          el('span', 'badge', `Head: ${plan.headBranch || 'evo/repair'}`),
          plan.publishPlanDigest ? el('span', 'small mono muted', `Plan ${M.shortDigest(plan.publishPlanDigest)}`) : null
        );
        host.append(meta);

        const titleInput = el('input', 'rs-input');
        titleInput.value = plan.title || 'Architecture repair proposal';
        const bodyInput = el('textarea', 'rs-textarea mono');
        bodyInput.rows = 7;
        bodyInput.value = plan.bodyMarkdown || '';
        const draftCheck = el('input');
        draftCheck.type = 'checkbox';
        draftCheck.checked = Boolean(plan.draft);

        const ackCheck = el('input');
        ackCheck.type = 'checkbox';
        ackCheck.checked = Boolean(studio.unverifiedAck);
        ackCheck.addEventListener('change', () => { studio.unverifiedAck = ackCheck.checked; });

        const remoteConfirmCheck = el('input');
        remoteConfirmCheck.type = 'checkbox';
        const publishHint = el('p', 'small muted');
        const publishBtn = btn('Confirm Push & Open Verified GitHub PR', async () => {
          publishBtn.disabled = true;
          planStatus.textContent = 'Pushing branch and opening GitHub Pull Request…';
          try {
            studio.prResult = await api('/github/repairs/publish', {
              confirmationToken: plan.confirmationToken,
              publishPlanDigest: plan.publishPlanDigest,
              confirmedByUser: true,
              title: titleInput.value.trim(),
              bodyMarkdown: bodyInput.value,
              draft: draftCheck.checked,
              ...(isUnverified ? { allowUnverified: ackCheck.checked } : {})
            }, env.signal, 'POST', 30000);
            window.EvoToast?.push?.({ kind: 'ok', title: 'GitHub Pull Request created' });
            renderGitHubPrForm(host, preview, effectiveVerdict);
          } catch (err) {
            planStatus.textContent = err.message || 'Could not publish PR.';
            syncPrValid();
          }
        }, 'button primary');

        const syncPrValid = () => {
          const evalRes = M.evaluateConfirmationGate(
            { ...(preview || {}), verdict: effectiveVerdict },
            {
              mode: 'GITHUB_PR',
              prTitle: titleInput.value,
              userConfirmed: remoteConfirmCheck.checked,
              remoteConfirmed: remoteConfirmCheck.checked,
              acknowledgeUnverified: ackCheck.checked
            }
          );
          publishBtn.disabled = !evalRes.allowed;
          publishHint.textContent = evalRes.allowed ? 'Ready to push and open Pull Request.' : evalRes.blockers[0];
        };
        titleInput.addEventListener('input', syncPrValid);
        ackCheck.addEventListener('change', syncPrValid);
        remoteConfirmCheck.addEventListener('change', syncPrValid);
        syncPrValid();

        append(host,
          append(el('label', 'rs-field'), el('span', 'small muted', 'Pull Request title'), titleInput),
          append(el('label', 'rs-field'), el('span', 'small muted', 'Pull Request description (Markdown)'), bodyInput),
          append(el('label', 'gh-confirm-check'), draftCheck, el('span', '', 'Open as Draft Pull Request')),
          isUnverified ? append(el('label', 'gh-confirm-check rs-unverified-ack'), ackCheck,
            el('span', '', 'Allow publishing an UNVERIFIED patch (truthful unverified warning remains in PR body)')) : null,
          append(el('label', 'gh-confirm-check'), remoteConfirmCheck,
            el('span', '', `Separate Remote Confirmation: I confirm pushing branch "${plan.headBranch || 'repair'}" to GitHub and opening this Pull Request.`)),
          append(el('div', 'rs-action-row'), publishBtn),
          publishHint,
          planStatus
        );
      }

      if (studio.applyPreview && studio.applyPreviewProposalId === propId) {
        renderGateControls(studio.applyPreview);
      } else {
        studio.applyPreview = null;
        studio.applyPreviewProposalId = null;
        previewBox.replaceChildren(el('p', 'small muted', 'Checking local file hashes, Git working tree status, and approval token…'));
        loadApplyPreview().then(preview => {
          if (env.signal.aborted) return;
          studio.applyPreviewProposalId = propId;
          studio.applyPreview = preview || {
            beforeHashesValid: true,
            verdict: studio.verifyReport?.verdict || 'UNVERIFIED',
            trustLabel: studio.verifyReport?.trustLabel || 'UNVERIFIED_CANDIDATE',
            suggestedBranch: `evo/repair-${String(propId).slice(-8)}`
          };
          renderGateControls(studio.applyPreview);
        }).catch(err => {
          if (env.signal.aborted) return;
          previewBox.replaceChildren(el('p', 'small muted', err.message || 'Apply preview unavailable from server; showing local safety gate.'));
          studio.applyPreviewProposalId = propId;
          studio.applyPreview = {
            beforeHashesValid: true,
            verdict: studio.verifyReport?.verdict || 'UNVERIFIED',
            trustLabel: studio.verifyReport?.trustLabel || 'UNVERIFIED_CANDIDATE',
            suggestedBranch: `evo/repair-${String(propId).slice(-8)}`
          };
          renderGateControls(studio.applyPreview);
        });
      }
    }

    function renderStage() {
      if (studio.step === 1) renderStep1();
      else if (studio.step === 2) renderStep2();
      else if (studio.step === 3) renderStep3();
      else if (studio.step === 4) renderStep4();
      else renderStep5();
    }

    function renderAll() {
      renderStepper();
      renderStage();
    }

    renderAll();
    return { selectFinding, openStep: step => { studio.step = step; renderAll(); } };
  }

  window.EvoRepairStudio = {
    mount: mountRepairStudio,
    renderMultiFileDiff,
    renderVerificationReportCard
  };
})();
