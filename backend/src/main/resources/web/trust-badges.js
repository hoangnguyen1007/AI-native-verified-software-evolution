/* Layer-0 Canonical TrustTier Taxonomy & Accessible DOM Pill Builder (0 Fetch, pure DOM). */
(() => {
  'use strict';

  const TIERS = Object.freeze(['COMPILER', 'SPRING_SAT', 'SOURCE_SYNTAX', 'ADVISORY_UNVERIFIED']);

  const DEFINITIONS = Object.freeze({
    COMPILER: Object.freeze({
      tier: 'COMPILER',
      label: 'Compiler',
      shortLabel: 'Compiler',
      className: 'evo-trust-badge is-compiler',
      tone: 'compiler',
      authoritative: true,
      quarantined: false,
      colorHex: '#4fd19a',
      lightColorHex: '#146c4e',
      description: 'Compiler-typed resolution backed by exact classpath, bytecode, AST+symbol solver, or verified index.scip.'
    }),
    SPRING_SAT: Object.freeze({
      tier: 'SPRING_SAT',
      label: 'Spring SAT',
      shortLabel: 'Spring SAT',
      className: 'evo-trust-badge is-spring-sat',
      tone: 'spring-sat',
      authoritative: true,
      quarantined: false,
      colorHex: '#a5b4fc',
      lightColorHex: '#4338ca',
      description: 'Bounded phase/order-aware Spring configuration-space and DPLL SAT truth-region evidence.'
    }),
    SOURCE_SYNTAX: Object.freeze({
      tier: 'SOURCE_SYNTAX',
      label: 'Source Syntax',
      shortLabel: 'Syntax',
      className: 'evo-trust-badge is-source-syntax',
      tone: 'source-syntax',
      authoritative: false,
      quarantined: false,
      colorHex: '#fbbf24',
      lightColorHex: '#92400e',
      description: 'Passive syntax-only CST/AST extraction without compiler/typechecker binding.'
    }),
    ADVISORY_UNVERIFIED: Object.freeze({
      tier: 'ADVISORY_UNVERIFIED',
      label: 'Advisory · Unverified',
      shortLabel: 'Advisory',
      className: 'evo-trust-badge is-advisory',
      tone: 'advisory',
      authoritative: false,
      quarantined: true,
      colorHex: '#67d8e8',
      lightColorHex: '#0c6478',
      description: 'Quarantined deterministic non-code or heuristic cross-layer overlay. Excluded from canonical graph and scores.'
    })
  });

  const SPRING_CLASSIFICATIONS = new Set(['MUST', 'MAY', 'NEVER', 'UNKNOWN']);

  function isValid(tier) {
    return typeof tier === 'string' && Object.prototype.hasOwnProperty.call(DEFINITIONS, tier);
  }

  /**
   * Normalizes a raw trust-tier value. Never promotes unknown/heuristic labels to COMPILER.
   */
  function normalize(raw) {
    if (typeof raw !== 'string') return 'SOURCE_SYNTAX';
    const cleaned = raw.trim().toUpperCase().replace(/[\s-]+/g, '_');
    if (Object.prototype.hasOwnProperty.call(DEFINITIONS, cleaned)) return cleaned;
    if (cleaned.includes('ADVISORY') || cleaned.includes('UNVERIFIED')) return 'ADVISORY_UNVERIFIED';
    if (cleaned.includes('SPRING') || cleaned === 'SAT') return 'SPRING_SAT';
    if (cleaned.includes('SYNTAX') || cleaned.includes('PRELIMINARY') || cleaned.includes('TREE_SITTER')) return 'SOURCE_SYNTAX';
    return 'SOURCE_SYNTAX';
  }

  function meta(tier) {
    const key = isValid(tier) ? tier : normalize(tier);
    return DEFINITIONS[key];
  }

  /**
   * Conservatively infers a canonical TrustTier from a graph/citation record when trustTier is absent.
   * Promotion Guard: never promotes advisory/preliminary/syntax records to COMPILER.
   */
  function inferFromRecord(record) {
    if (!record || typeof record !== 'object') return 'SOURCE_SYNTAX';
    const explicit = record.trustTier || record.properties?.trustTier;
    if (typeof explicit === 'string' && explicit.trim()) return normalize(explicit);

    const kind = String(record.kind || '').toUpperCase();
    const derivation = String(record.derivation || record.properties?.derivation || '').toUpperCase();
    if (kind.includes('ADVISORY') || kind.includes('SQL_') || kind.includes('TERRAFORM') || kind.includes('MARKDOWN') || kind.includes('DOC_LINK')) {
      return 'ADVISORY_UNVERIFIED';
    }
    if (kind.startsWith('SPRING') || kind.includes('BEAN') || String(record.scope || '').toUpperCase() === 'SPRING_COMPONENT') {
      return 'SPRING_SAT';
    }
    if (derivation.includes('SYNTAX') || derivation.includes('PRELIMINARY') || record.preliminary) {
      return 'SOURCE_SYNTAX';
    }
    if (kind.startsWith('JAVA') || kind === 'TYPE' || kind === 'PACKAGE' || kind === 'MODULE' || kind === 'COMMUNITY_SUPER_NODE') {
      return record.boundCallTargets === false ? 'SOURCE_SYNTAX' : 'COMPILER';
    }
    return 'SOURCE_SYNTAX';
  }

  /**
   * Pure DOM pill builder (textContent and createElement only).
   * @param {string} tier One of COMPILER | SPRING_SAT | SOURCE_SYNTAX | ADVISORY_UNVERIFIED
   * @param {object} [extra] Optional { compact, classification, suffix, title, className, doc }
   */
  function renderBadge(tier, extra = {}) {
    const doc = extra?.doc || (typeof document !== 'undefined' ? document : null);
    if (!doc) throw new Error('EvoTrust.renderBadge requires a DOM document.');
    const info = meta(tier);
    const pill = doc.createElement('span');
    const clsParts = [info.className];
    if (extra?.compact) clsParts.push('is-compact');
    if (extra?.className) clsParts.push(String(extra.className));
    pill.className = clsParts.join(' ');
    pill.dataset.trustTier = info.tier;

    const dot = doc.createElement('span');
    dot.className = 'evo-trust-dot';
    dot.setAttribute('aria-hidden', 'true');

    const text = doc.createElement('span');
    text.className = 'evo-trust-label';
    text.textContent = extra?.compact ? info.shortLabel : info.label;

    pill.append(dot, text);

    // Strictly orthogonal Spring classification sub-pill (never conflated with TrustTier).
    const rawClass = typeof extra?.classification === 'string' ? extra.classification.trim().toUpperCase() : '';
    if (rawClass && SPRING_CLASSIFICATIONS.has(rawClass)) {
      const sub = doc.createElement('span');
      sub.className = 'evo-trust-subpill is-' + rawClass.toLowerCase();
      sub.textContent = rawClass;
      pill.append(sub);
    }

    if (extra?.suffix) {
      const suf = doc.createElement('span');
      suf.className = 'evo-trust-suffix';
      suf.textContent = String(extra.suffix);
      pill.append(suf);
    }

    const fullLabel = 'Trust tier: ' + info.label
      + (rawClass && SPRING_CLASSIFICATIONS.has(rawClass) ? ' (' + rawClass + ')' : '')
      + (extra?.suffix ? ' ' + String(extra.suffix) : '');
    pill.setAttribute('aria-label', fullLabel);
    pill.title = extra?.title ? String(extra.title) : (info.label + ' — ' + info.description);
    return pill;
  }

  const api = Object.freeze({
    TIERS,
    DEFINITIONS,
    isValid,
    normalize,
    meta,
    inferFromRecord,
    renderBadge
  });

  if (typeof window !== 'undefined') {
    window.EvoTrust = api;
  }
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})();
