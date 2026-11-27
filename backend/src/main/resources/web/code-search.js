/* Layer-3 Modular Hybrid Code Search (FTS5/BM25) & RRF Breakdown UI
 * Strict zero-XSS (pure DOM APIs only, no raw HTML injection), zero external CDN, CSP script-src 'self' compliant.
 */
(() => {
  'use strict';

  const DEFAULT_LIMIT = 20;
  const COMMON_LANGUAGES = ['java', 'typescript', 'javascript', 'python', 'go', 'kotlin', 'sql', 'yaml', 'markdown', 'terraform'];

  const TRUST_META = Object.freeze({
    COMPILER: { label: 'Compiler', cls: 'is-compiler', title: 'Compiler-typed resolution with exact classpath, bytecode, or verified SCIP artifact' },
    SPRING_SAT: { label: 'Spring SAT', cls: 'is-spring-sat', title: 'Bounded phase/order-aware Spring configuration-space SAT truth region' },
    SOURCE_SYNTAX: { label: 'Source Syntax', cls: 'is-source-syntax', title: 'Passive syntax-only CST/AST extraction or captured source text' },
    ADVISORY_UNVERIFIED: { label: 'Advisory · Unverified', cls: 'is-advisory', title: 'Quarantined non-code / advisory overlay — excluded from canonical graph digests & Score v2' }
  });

  function el(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text != null) node.textContent = String(text);
    return node;
  }

  function btn(text, onClick, cls = 'button subtle') {
    const node = el('button', cls, text);
    node.type = 'button';
    if (typeof onClick === 'function') node.addEventListener('click', onClick);
    return node;
  }

  function shortSha(digest) {
    const raw = String(digest || '');
    const hex = raw.includes(':') ? raw.split(':').at(-1) : raw;
    return hex.length > 10 ? hex.slice(0, 8) : hex;
  }

  function finiteNumber(value) {
    if (value === null || value === undefined || value === '') return null;
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }

  function formatScore(value, digits = 3) {
    const n = finiteNumber(value);
    if (n === null) return '';
    if (Math.abs(n) >= 100) return n.toFixed(1);
    if (Math.abs(n) >= 10) return n.toFixed(2);
    return n.toFixed(digits);
  }

  /**
   * Renders a canonical TrustTier pill aligned with the 4-tier epistemic taxonomy.
   */
  function renderTrustBadge(trustTier, isAdvisory = false) {
    const normalized = String(trustTier || (isAdvisory ? 'ADVISORY_UNVERIFIED' : '')).trim().toUpperCase();
    const meta = TRUST_META[normalized] || (isAdvisory ? TRUST_META.ADVISORY_UNVERIFIED : null);
    if (!meta) return null;
    const pill = el('span', 'evo-trust-badge ' + meta.cls, meta.label);
    pill.title = meta.title;
    pill.dataset.trustTier = normalized || 'ADVISORY_UNVERIFIED';
    return pill;
  }

  /**
   * Renders RRF score breakdown badges (BM25, PPR, RRF, optional Trigram) when present on RepoMap
   * or hybrid code-search items (supports both rank fields pprRank/bm25Rank/trigramRank and numeric scores).
   * Returns null for classic v1 items with no breakdown fields.
   */
  function renderRrfBadges(item) {
    if (!item || typeof item !== 'object') return null;
    const breakdown = item.scoreBreakdown && typeof item.scoreBreakdown === 'object' ? item.scoreBreakdown : item;
    const bm25Rank = finiteNumber(breakdown.bm25Rank ?? item.bm25Rank);
    const pprRank = finiteNumber(breakdown.pprRank ?? item.pprRank);
    const trigramRank = finiteNumber(breakdown.trigramRank ?? item.trigramRank);
    const bm25 = finiteNumber(breakdown.bm25Score ?? breakdown.bm25 ?? item.bm25Score ?? item.bm25);
    const ppr = finiteNumber(breakdown.pprScore ?? breakdown.ppr ?? item.pprScore ?? item.ppr);
    const rrf = finiteNumber(breakdown.rrfScore ?? breakdown.rrf ?? item.rrfScore ?? item.rrf);
    const trigram = finiteNumber(breakdown.trigramScore ?? breakdown.trigram ?? item.trigramScore ?? item.trigram);

    const hasRankOrScore = bm25Rank !== null || bm25 !== null || rrf !== null || trigramRank !== null
      || (pprRank !== null && (bm25Rank !== null || rrf !== null || trigramRank !== null))
      || ppr !== null || (trigram !== null && trigram > 0);
    if (!hasRankOrScore) return null;

    const row = el('div', 'evo-rrf-badges');
    row.setAttribute('aria-label', 'Hybrid Reciprocal Rank Fusion score breakdown');

    if (bm25Rank !== null) {
      const pill = el('span', 'evo-rrf-pill is-bm25', 'BM25 #' + Math.round(bm25Rank));
      pill.title = 'SQLite FTS5 BM25 lexical rank';
      row.append(pill);
    } else if (bm25 !== null) {
      const pill = el('span', 'evo-rrf-pill is-bm25', 'BM25 ' + formatScore(bm25, 2));
      pill.title = 'SQLite FTS5 BM25 lexical relevance score';
      row.append(pill);
    }
    if (pprRank !== null) {
      const pill = el('span', 'evo-rrf-pill is-ppr', 'PPR #' + Math.round(pprRank));
      pill.title = 'Personalized PageRank structural centrality rank';
      row.append(pill);
    } else if (ppr !== null) {
      const pill = el('span', 'evo-rrf-pill is-ppr', 'PPR ' + formatScore(ppr, 4));
      pill.title = 'Personalized PageRank structural centrality score';
      row.append(pill);
    }
    if (trigramRank !== null) {
      const pill = el('span', 'evo-rrf-pill is-trigram', 'TRI #' + Math.round(trigramRank));
      pill.title = 'Sub-token trigram identifier match rank';
      row.append(pill);
    } else if (trigram !== null && trigram > 0) {
      const pill = el('span', 'evo-rrf-pill is-trigram', 'TRI ' + formatScore(trigram, 3));
      pill.title = 'Sub-token trigram identifier match score';
      row.append(pill);
    }
    if (rrf !== null) {
      const pill = el('span', 'evo-rrf-pill is-rrf', 'RRF ' + formatScore(rrf, 4));
      pill.title = 'Fused Reciprocal Rank Fusion score';
      row.append(pill);
    }
    return row.childElementCount ? row : null;
  }

  /**
   * Tokenizes the user search query into safe literal highlight terms (no regex injection, pure DOM Text nodes).
   */
  function extractHighlightTerms(query) {
    if (!query || typeof query !== 'string') return [];
    const seen = new Set();
    const out = [];
    for (const raw of query.split(/[\s"'(),;:{}[\]]+/)) {
      const clean = raw.replace(/^[*+-]+|[*+-]+$/g, '').trim();
      if (clean.length < 2) continue;
      const upper = clean.toUpperCase();
      if (upper === 'AND' || upper === 'OR' || upper === 'NOT' || upper === 'NEAR') continue;
      const lower = clean.toLowerCase();
      if (!seen.has(lower)) {
        seen.add(lower);
        out.push(lower);
      }
      if (out.length >= 8) break;
    }
    out.sort((a, b) => b.length - a.length);
    return out;
  }

  function appendHighlightedLine(parent, text, terms) {
    if (!terms.length || !text) {
      parent.append(document.createTextNode(text || ' '));
      return;
    }
    const lower = text.toLowerCase();
    let cursor = 0;
    while (cursor < text.length) {
      let bestIdx = -1;
      let bestLen = 0;
      for (const term of terms) {
        const idx = lower.indexOf(term, cursor);
        if (idx !== -1 && (bestIdx === -1 || idx < bestIdx || (idx === bestIdx && term.length > bestLen))) {
          bestIdx = idx;
          bestLen = term.length;
        }
      }
      if (bestIdx === -1) {
        parent.append(document.createTextNode(text.slice(cursor)));
        break;
      }
      if (bestIdx > cursor) {
        parent.append(document.createTextNode(text.slice(cursor, bestIdx)));
      }
      parent.append(el('mark', 'evo-cs-hl', text.slice(bestIdx, bestIdx + bestLen)));
      cursor = bestIdx + bestLen;
    }
  }

  function renderSnippet(snippetText, startLine, terms) {
    const box = el('div', 'evo-cs-snippet');
    box.setAttribute('role', 'region');
    box.setAttribute('aria-label', 'Code snippet');
    const pre = el('pre', 'evo-cs-snippet-pre');
    const code = el('code', 'evo-cs-snippet-code');
    const baseLine = Math.max(1, Number(startLine) || 1);
    const lines = String(snippetText || '').replace(/\r\n?/g, '\n').split('\n');
    const maxLines = Math.min(lines.length, 16);
    for (let i = 0; i < maxLines; i++) {
      const row = el('div', 'evo-cs-line');
      const num = el('span', 'evo-cs-ln', String(baseLine + i));
      num.setAttribute('aria-hidden', 'true');
      const content = el('span', 'evo-cs-code-text');
      appendHighlightedLine(content, lines[i], terms);
      row.append(num, content);
      code.append(row);
    }
    pre.append(code);
    box.append(pre);
    return box;
  }

  function buildAskAiPrompt(item, query) {
    const start = Math.max(1, Number(item.startLine) || 1);
    const end = Math.max(start, Number(item.endLine) || start);
    const lineRef = end > start ? `lines ${start}-${end}` : `line ${start}`;
    const trustNote = item.isAdvisory || item.trustTier === 'ADVISORY_UNVERIFIED'
      ? ' (note: this match comes from an ADVISORY_UNVERIFIED artifact)'
      : '';
    return `Explain the architectural role, dependencies, and any risks of \`${item.path}\` around ${lineRef} matching "${query}"${trustNote}. Cite captured evidence IDs.`;
  }

  /**
   * Mounts the interactive Hybrid Code Search (FTS5/BM25) panel inside {@code container}.
   */
  function mountCodeSearch(container, scope) {
    if (!container || !scope || typeof scope.api !== 'function') return null;

    const state = {
      query: '',
      selectedLanguages: new Set(),
      knownLanguages: new Set(Array.isArray(scope.languages) ? scope.languages.filter(Boolean) : COMMON_LANGUAGES),
      includeAdvisory: false,
      limit: Number(scope.limit) > 0 ? Math.min(50, Number(scope.limit)) : DEFAULT_LIMIT,
      offset: 0,
      version: 0,
      lastResponse: null
    };

    const root = el('div', 'evo-cs-root');

    // Search form
    const form = el('form', 'evo-cs-form');
    form.setAttribute('role', 'search');
    form.setAttribute('aria-label', 'Hybrid code search');

    const inputWrap = el('div', 'evo-cs-input-wrap');
    const queryInput = el('input', 'evo-cs-input');
    queryInput.type = 'search';
    queryInput.name = 'codeSearchQuery';
    queryInput.placeholder = 'Search captured source (FTS5/BM25: symbols, annotations, SQL tables, error codes)…';
    queryInput.setAttribute('aria-label', 'Search captured source code');
    queryInput.autocomplete = 'off';
    queryInput.spellcheck = false;

    const clearBtn = btn('Clear', () => {
      queryInput.value = '';
      state.query = '';
      state.offset = 0;
      state.lastResponse = null;
      renderIdle();
      queryInput.focus();
    }, 'button subtle evo-cs-clear');
    clearBtn.hidden = true;

    const submitBtn = el('button', 'button primary evo-cs-submit', 'Search code');
    submitBtn.type = 'submit';
    inputWrap.append(queryInput, clearBtn, submitBtn);

    // Controls row: Advisory toggle + Language filter chips
    const controls = el('div', 'evo-cs-controls');
    const advisoryLabel = el('label', 'evo-cs-advisory-toggle');
    advisoryLabel.title = 'Include quarantined non-code artifacts (Markdown ADRs, SQL DDL, Terraform IaC, MCP configs) tagged ADVISORY_UNVERIFIED.';
    const advisoryInput = el('input');
    advisoryInput.type = 'checkbox';
    advisoryInput.name = 'includeAdvisory';
    advisoryInput.checked = state.includeAdvisory;
    advisoryInput.addEventListener('change', () => {
      state.includeAdvisory = advisoryInput.checked;
      state.offset = 0;
      if (state.query) void runSearch();
    });
    advisoryLabel.append(advisoryInput, el('span', '', 'Include Advisory (Docs, SQL, IaC)'));

    const langRow = el('div', 'evo-cs-lang-chips');
    langRow.setAttribute('role', 'group');
    langRow.setAttribute('aria-label', 'Filter by language');
    controls.append(advisoryLabel, langRow);

    form.append(inputWrap, controls);

    const statusBar = el('div', 'evo-cs-status-bar');
    statusBar.setAttribute('role', 'status');
    statusBar.setAttribute('aria-live', 'polite');

    const noticeBanner = el('div', 'evo-cs-honesty-banner');
    noticeBanner.setAttribute('role', 'status');
    noticeBanner.hidden = true;

    const resultsHost = el('div', 'evo-cs-results');
    const pagerHost = el('div', 'evo-cs-pager');
    pagerHost.hidden = true;

    root.append(form, statusBar, noticeBanner, resultsHost, pagerHost);
    container.replaceChildren(root);

    function renderLanguageChips() {
      langRow.replaceChildren();
      const allChip = btn('All', () => {
        state.selectedLanguages.clear();
        state.offset = 0;
        renderLanguageChips();
        if (state.query) void runSearch();
      }, 'chip evo-cs-lang-chip' + (state.selectedLanguages.size === 0 ? ' active' : ''));
      allChip.setAttribute('aria-pressed', String(state.selectedLanguages.size === 0));
      langRow.append(allChip);

      const ordered = [...state.knownLanguages].sort((a, b) => a.localeCompare(b));
      for (const lang of ordered.slice(0, 14)) {
        const active = state.selectedLanguages.has(lang);
        const chip = btn(lang, () => {
          if (state.selectedLanguages.has(lang)) state.selectedLanguages.delete(lang);
          else state.selectedLanguages.add(lang);
          state.offset = 0;
          renderLanguageChips();
          if (state.query) void runSearch();
        }, 'chip evo-cs-lang-chip' + (active ? ' active' : ''));
        chip.setAttribute('aria-pressed', String(active));
        langRow.append(chip);
      }
    }

    function renderIdle() {
      clearBtn.hidden = !queryInput.value.trim();
      statusBar.replaceChildren(el('span', 'muted small', 'Pre-redacted SQLite FTS5 / BM25 index over immutable captured repository source.'));
      noticeBanner.hidden = true;
      pagerHost.hidden = true;
      const box = el('div', 'evo-cs-empty');
      box.append(
        el('h3', '', 'Search captured source code'),
        el('p', 'muted', 'Type a symbol, annotation, class name, endpoint, or phrase and press Enter. Secrets are pre-redacted before indexing and ADVISORY_UNVERIFIED files remain opt-in.')
      );
      resultsHost.replaceChildren(box);
    }

    function renderResponse(data) {
      const items = Array.isArray(data?.items) ? data.items : [];
      const totalHits = Number.isFinite(Number(data?.totalHits)) ? Number(data.totalHits) : items.length;
      const engine = data?.engine || 'SQLITE_FTS5_BM25';
      const omittedFiles = Number(data?.omittedFiles) || 0;
      const truncated = Boolean(data?.truncated);
      const redactedSecretCount = Number(data?.redactedSecretCount) || 0;

      // Harvest newly discovered languages for filter chips
      let addedLang = false;
      for (const item of items) {
        if (item?.language && typeof item.language === 'string' && !state.knownLanguages.has(item.language)) {
          state.knownLanguages.add(item.language);
          addedLang = true;
        }
      }
      if (addedLang) renderLanguageChips();

      // Status bar
      statusBar.replaceChildren();
      const enginePill = el('span', 'evo-cs-engine-pill', engine);
      enginePill.title = 'Active full-text search backend';
      const rangeStart = items.length ? state.offset + 1 : 0;
      const rangeEnd = state.offset + items.length;
      const hitSummary = items.length
        ? `Showing ${rangeStart}–${rangeEnd} of ${totalHits.toLocaleString()} hit${totalHits === 1 ? '' : 's'}`
        : `0 hits for "${state.query}"`;
      statusBar.append(enginePill, el('span', 'small muted', hitSummary));
      if (redactedSecretCount > 0) {
        const redactPill = el('span', 'evo-cs-redact-pill',
          `${redactedSecretCount.toLocaleString()} secret${redactedSecretCount === 1 ? '' : 's'} pre-redacted`);
        redactPill.title = 'Secret-like literals were stripped before FTS5 persistence and are never indexed or returned.';
        statusBar.append(redactPill);
      }

      // Epistemic honesty banner for omittedFiles / engine-ceiling truncation (not normal pagination)
      const hasMorePages = (state.offset + items.length) < totalHits;
      const engineCeilingTruncated = truncated && (!hasMorePages || data?.engineCeilingReached === true || totalHits >= 1000);
      const notices = [];
      if (omittedFiles > 0) {
        notices.push(`${omittedFiles.toLocaleString()} captured file${omittedFiles === 1 ? '' : 's'} omitted from FTS5 index (binary, unreadable, or above per-file cap)`);
      }
      if (engineCeilingTruncated) {
        notices.push('Search hit the engine match ceiling — narrow the query or language filters for exhaustive coverage');
      }
      if (notices.length) {
        noticeBanner.replaceChildren(
          el('strong', '', 'Coverage boundary: '),
          document.createTextNode(notices.join(' · ') + '.')
        );
        noticeBanner.hidden = false;
      } else {
        noticeBanner.hidden = true;
      }

      if (!items.length) {
        const box = el('div', 'evo-cs-empty');
        box.append(
          el('h3', '', 'No matching source snippets'),
          el('p', 'muted', state.includeAdvisory
            ? 'Try broadening your query or clearing language filters.'
            : 'Try broadening your query, clearing language filters, or enabling "Include Advisory" to search Markdown, SQL, and IaC files.')
        );
        resultsHost.replaceChildren(box);
        pagerHost.hidden = true;
        return;
      }

      const terms = extractHighlightTerms(state.query);
      const list = el('div', 'evo-cs-list');
      for (const item of items) {
        const card = el('article', 'evo-cs-card' + (item.isAdvisory || item.trustTier === 'ADVISORY_UNVERIFIED' ? ' is-advisory' : ''));
        const head = el('div', 'evo-cs-card-head');

        const startLine = Math.max(1, Number(item.startLine) || 1);
        const endLine = Math.max(startLine, Number(item.endLine) || startLine);
        const lineText = endLine > startLine ? `L${startLine}–L${endLine}` : `L${startLine}`;

        const pathBtn = btn(item.path || '(unknown path)', () => {
          if (typeof scope.openSource === 'function' && item.path) {
            scope.openSource(item.path, Math.max(1, startLine - 10), Math.max(endLine + 30, startLine + 40), startLine);
          }
        }, 'evo-cs-path-btn');
        pathBtn.title = 'Open captured source at line ' + startLine;

        const metaPills = el('div', 'evo-cs-card-pills');
        metaPills.append(el('span', 'evo-cs-line-pill', lineText));
        if (item.language) metaPills.append(el('span', 'badge', item.language));
        const trustPill = renderTrustBadge(item.trustTier, Boolean(item.isAdvisory));
        if (trustPill) metaPills.append(trustPill);
        if (Number(item.redactedCount) > 0) {
          const rPill = el('span', 'evo-cs-redact-pill', `${Number(item.redactedCount)} redacted`);
          rPill.title = 'Secret-like token(s) pre-redacted in this file snippet';
          metaPills.append(rPill);
        }
        const scoreVal = finiteNumber(item.score);
        if (scoreVal !== null) {
          const sPill = el('span', 'evo-cs-score-pill', 'Score ' + formatScore(scoreVal, 3));
          sPill.title = 'BM25 / Hybrid relevance score';
          metaPills.append(sPill);
        }
        if (item.contentSha256) {
          const sha = el('code', 'evo-cs-sha', shortSha(item.contentSha256));
          sha.title = 'Content SHA-256: ' + item.contentSha256;
          metaPills.append(sha);
        }

        head.append(pathBtn, metaPills);
        card.append(head);

        const rrfRow = renderRrfBadges(item);
        if (rrfRow) card.append(rrfRow);

        if (item.snippet != null && String(item.snippet).length > 0) {
          card.append(renderSnippet(item.snippet, startLine, terms));
        }

        const actions = el('div', 'evo-cs-card-actions');
        if (typeof scope.openSource === 'function' && item.path) {
          actions.append(btn('Open source @ ' + lineText, () => {
            scope.openSource(item.path, Math.max(1, startLine - 10), Math.max(endLine + 30, startLine + 40), startLine);
          }, 'button subtle evo-cs-action-btn'));
        }
        if (typeof scope.askAi === 'function' && item.path) {
          actions.append(btn('Ask AI', () => {
            scope.askAi(buildAskAiPrompt(item, state.query));
          }, 'button subtle evo-cs-action-btn evo-cs-ask-btn'));
        }
        if (actions.childElementCount) card.append(actions);
        list.append(card);
      }

      resultsHost.replaceChildren(list);

      // Pagination
      pagerHost.replaceChildren();
      const hasPrev = state.offset > 0;
      const hasNext = state.offset + items.length < totalHits;
      if (hasPrev || hasNext) {
        if (hasPrev) {
          pagerHost.append(btn('← Previous', () => {
            state.offset = Math.max(0, state.offset - state.limit);
            void runSearch();
          }, 'button subtle'));
        }
        pagerHost.append(el('span', 'small muted', `Page ${Math.floor(state.offset / state.limit) + 1} of ${Math.max(1, Math.ceil(totalHits / state.limit))}`));
        if (hasNext) {
          pagerHost.append(btn('Next →', () => {
            state.offset += state.limit;
            void runSearch();
          }, 'button subtle'));
        }
        pagerHost.hidden = false;
      } else {
        pagerHost.hidden = true;
      }
    }

    async function runSearch() {
      const q = queryInput.value.trim();
      state.query = q;
      clearBtn.hidden = !q;
      if (!q) {
        renderIdle();
        return;
      }
      const current = ++state.version;
      submitBtn.disabled = true;
      statusBar.replaceChildren(el('span', 'muted small', `Searching FTS5 index for "${q}"…`));
      noticeBanner.hidden = true;
      const loading = el('div', 'evo-cs-loading', 'Querying pre-redacted FTS5 index…');
      resultsHost.replaceChildren(loading);
      pagerHost.hidden = true;
      try {
        const payload = {
          query: q,
          includeAdvisory: state.includeAdvisory,
          limit: state.limit,
          offset: state.offset
        };
        if (state.selectedLanguages.size > 0) {
          payload.languages = [...state.selectedLanguages];
        }
        const data = await scope.api('/graphs/' + scope.graph + '/queries/code-search', payload, scope.signal, 'POST');
        if (scope.signal?.aborted || current !== state.version) return;
        state.lastResponse = data;
        renderResponse(data);
      } catch (err) {
        if (scope.signal?.aborted || current !== state.version) return;
        statusBar.replaceChildren(el('span', 'small evo-cs-err-text', 'Code search failed.'));
        const errBox = el('div', 'evo-cs-empty');
        errBox.append(
          el('h3', '', 'Code search unavailable'),
          el('p', 'muted', err?.message || 'Unable to query /queries/code-search on this snapshot.'),
          btn('Retry search', () => void runSearch(), 'button subtle')
        );
        resultsHost.replaceChildren(errBox);
      } finally {
        if (!scope.signal?.aborted && current === state.version) {
          submitBtn.disabled = false;
        }
      }
    }

    form.addEventListener('submit', event => {
      event.preventDefault();
      state.offset = 0;
      void runSearch();
    });
    queryInput.addEventListener('input', () => {
      clearBtn.hidden = !queryInput.value.trim();
    });

    renderLanguageChips();
    renderIdle();

    return Object.freeze({
      focus: () => queryInput.focus(),
      search: (q) => {
        queryInput.value = String(q || '');
        state.offset = 0;
        return runSearch();
      }
    });
  }

  window.EvoCodeSearch = Object.freeze({
    mountCodeSearch,
    renderRrfBadges,
    renderTrustBadge
  });
})();
