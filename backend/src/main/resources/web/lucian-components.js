/* Lucian UX/UI Prompt #403 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#403 */
/* Lucian UX/UI Prompt #404 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#404 */
/* Lucian UX/UI Prompt #062 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#062 */
/* Lucian UX/UI Prompt #416 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#416 */
/* Lucian UX/UI Prompt #408 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#408 */
/* Lucian UX/UI Prompt #013 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#013 */
/* Lucian UX/UI Prompt #439 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#439 */
/* Lucian UX/UI Prompt #044 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#044 */
(() => {
  'use strict';
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const isReducedMotion = () => Boolean(window.matchMedia?.('(prefers-reduced-motion: reduce)').matches);
  let uidSeq = 0;
  const nextUid = prefix => `${prefix}-${++uidSeq}`;

  function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = String(text);
    return n;
  }
  function svgEl(tag, attrs = {}) {
    const n = document.createElementNS(SVG_NS, tag);
    for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, String(v));
    return n;
  }

  function buildEyeSvg() {
    const svg = svgEl('svg', { viewBox: '0 0 20 20', 'aria-hidden': 'true' });
    const lid = svgEl('g', { class: 'lid' });
    lid.append(
      svgEl('path', { d: 'M2 10s3-5.2 8-5.2S18 10 18 10s-3 5.2-8 5.2S2 10 2 10z' }),
      svgEl('circle', { cx: '10', cy: '10', r: '2.4' })
    );
    const lash = svgEl('g', { class: 'lash' });
    lash.append(svgEl('path', { d: 'M3 10.4c2 2.4 4.4 3.4 7 3.4s5-1 7-3.4M6 13l-1 1.6M10 14v1.8M14 13l1 1.6' }));
    svg.append(lid, lash);
    return svg;
  }

  function buildCheckCircleSvg() {
    const svg = svgEl('svg', { viewBox: '0 0 18 18', 'aria-hidden': 'true' });
    svg.append(
      svgEl('circle', { cx: '9', cy: '9', r: '8' }),
      svgEl('path', { d: 'M5.6 9.3l2.3 2.3 4.6-4.8' })
    );
    return svg;
  }

  /* ── #403 + #062 Floating Field with Blinking Eye & Caps Lock Warning ─── */
  function createFloatingField({
    label, type = 'text', id = nextUid('u403'), name = '', autocomplete = 'off',
    value = '', required = false, placeholder = '', withPasswordEye = false, withCapsWarning = false,
    onInput = null
  }) {
    const wrap = el('div', 'ux u403-f' + (withPasswordEye ? ' u403-pw' : ''));
    const input = el('input', 'u403-in');
    input.type = type;
    input.id = id;
    if (name) input.name = name;
    input.autocomplete = autocomplete;
    input.spellcheck = false;
    input.required = Boolean(required);
    if (placeholder) input.placeholder = placeholder;
    if (value) input.value = String(value);

    const lbl = el('label', '', label);
    lbl.htmlFor = id;
    wrap.append(input, lbl);

    let capsBadge = null;
    if (withCapsWarning) {
      capsBadge = el('span', 'u062-caps', 'Caps Lock');
      capsBadge.setAttribute('aria-live', 'polite');
      wrap.append(capsBadge);
      input.addEventListener('keyup', e => {
        if (e.getModifierState) capsBadge.classList.toggle('on', Boolean(e.getModifierState('CapsLock')));
      });
    }

    let eyeBtn = null;
    if (withPasswordEye) {
      eyeBtn = el('button', 'u403-eye');
      eyeBtn.type = 'button';
      eyeBtn.setAttribute('aria-label', 'Show password');
      eyeBtn.setAttribute('aria-pressed', 'false');
      eyeBtn.append(buildEyeSvg());
      eyeBtn.addEventListener('click', () => {
        const show = eyeBtn.getAttribute('aria-pressed') !== 'true';
        eyeBtn.setAttribute('aria-pressed', String(show));
        eyeBtn.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
        input.type = show ? 'text' : 'password';
      });
      wrap.append(eyeBtn);
    }

    const sync = () => {
      wrap.classList.toggle('is-up', wrap.classList.contains('is-f') || Boolean(input.value) || Boolean(placeholder));
    };
    input.addEventListener('focus', () => { wrap.classList.add('is-f'); sync(); });
    input.addEventListener('blur', () => { wrap.classList.remove('is-f'); sync(); });
    input.addEventListener('input', () => {
      if (wrap.classList.contains('is-err')) {
        wrap.classList.remove('is-err');
        input.removeAttribute('aria-invalid');
      }
      sync();
      onInput?.(input.value);
    });
    sync();

    return {
      el: wrap,
      input,
      sync,
      setValue(v) { input.value = String(v ?? ''); sync(); },
      getValue() { return input.value; },
      setError(isErr) {
        wrap.classList.toggle('is-err', Boolean(isErr));
        if (isErr) {
          input.setAttribute('aria-invalid', 'true');
          if (!isReducedMotion()) {
            wrap.classList.remove('is-shake');
            void wrap.offsetWidth;
            wrap.classList.add('is-shake');
          }
        } else {
          input.removeAttribute('aria-invalid');
        }
      },
      resetPasswordMask() {
        if (eyeBtn) {
          eyeBtn.setAttribute('aria-pressed', 'false');
          eyeBtn.setAttribute('aria-label', 'Show password');
          input.type = 'password';
        }
      }
    };
  }

  /* ── #404 Sign-Up Password Field with 4-Segment Meter & Rule Checklist ── */
  function createPasswordStrength({
    id = nextUid('u404'), label = 'Password', autocomplete = 'new-password', onChange = null
  } = {}) {
    const LV = ['', 'Weak', 'Fair', 'Strong', 'Very strong'];
    const CHECKS = [
      { text: '8+ characters', fn: v => v.length >= 8 },
      { text: 'A number', fn: v => /\d/.test(v) },
      { text: 'A symbol', fn: v => /[^A-Za-z0-9\s]/.test(v) }
    ];

    const root = el('div', 'ux u404-wrap');
    root.dataset.lv = '0';

    const field = createFloatingField({
      label, type: 'password', id, autocomplete, required: true,
      withPasswordEye: true, withCapsWarning: true,
      onInput: () => sync()
    });

    const stRow = el('div', 'u404-st');
    const meter = el('div', 'u404-bars');
    meter.setAttribute('role', 'meter');
    meter.setAttribute('aria-label', 'Password strength');
    meter.setAttribute('aria-valuemin', '0');
    meter.setAttribute('aria-valuemax', '4');
    meter.setAttribute('aria-valuenow', '0');
    const bars = [];
    for (let i = 0; i < 4; i++) {
      const b = el('i');
      bars.push(b);
      meter.append(b);
    }
    const levelLabel = el('em', '', '');
    stRow.append(meter, levelLabel);

    const ruleList = el('ul', 'u404-rules');
    const ruleEls = CHECKS.map((chk, idx) => {
      const li = el('li');
      li.id = `${id}-r${idx}`;
      li.append(buildCheckCircleSvg(), el('span', '', chk.text));
      ruleList.append(li);
      return li;
    });
    field.input.setAttribute('aria-describedby', ruleEls.map(r => r.id).join(' '));

    root.append(field.el, stRow, ruleList);

    let allValid = false;
    function computeScore(v) {
      if (!v) return 0;
      const pts = (v.length >= 8 ? 1 : 0) + (/\d/.test(v) ? 1 : 0) + (/[^A-Za-z0-9\s]/.test(v) ? 1 : 0)
        + ((v.length >= 12 || (/[a-z]/.test(v) && /[A-Z]/.test(v))) ? 1 : 0);
      return Math.max(1, pts);
    }
    function sync() {
      const v = field.input.value;
      const lv = computeScore(v);
      let okAll = true;
      CHECKS.forEach((chk, idx) => {
        const ok = chk.fn(v);
        ruleEls[idx].classList.toggle('is-ok', ok);
        okAll = okAll && ok;
      });
      allValid = okAll;
      root.dataset.lv = String(lv);
      bars.forEach((b, idx) => b.classList.toggle('is-on', idx < lv));
      meter.setAttribute('aria-valuenow', String(lv));
      meter.setAttribute('aria-valuetext', LV[lv] || '');
      levelLabel.textContent = LV[lv];
      field.sync();
      onChange?.({ value: v, level: lv, valid: okAll });
    }
    sync();

    return {
      el: root,
      input: field.input,
      field,
      sync,
      getValue: () => field.input.value,
      isValid: () => allValid,
      setError: isErr => field.setError(isErr)
    };
  }

  /* ── #403 / #062 Morphing Action Button (Idle -> Spinner -> Checkmark) ── */
  function createMorphButton({ label, type = 'submit', id = '', cls = '', onClick = null }) {
    const b = el('button', ('ux u403-go ' + cls).trim());
    b.type = type;
    if (id) b.id = id;
    const span = el('span', '', label);
    const spin = el('i');
    spin.setAttribute('aria-hidden', 'true');
    const svg = svgEl('svg', { viewBox: '0 0 22 22', 'aria-hidden': 'true' });
    svg.append(svgEl('path', { d: 'M5.5 11.5l4 4L17 7.5' }));
    b.append(span, spin, svg);
    if (typeof onClick === 'function') b.addEventListener('click', onClick);

    return {
      el: b,
      setLabel(txt) { span.textContent = String(txt); },
      setBusy(busy) {
        b.classList.toggle('is-busy', Boolean(busy));
        b.disabled = Boolean(busy);
        if (busy) {
          b.classList.remove('is-ok');
          b.setAttribute('aria-busy', 'true');
        } else {
          b.removeAttribute('aria-busy');
        }
      },
      setOk(ok) {
        b.classList.remove('is-busy');
        b.removeAttribute('aria-busy');
        b.classList.toggle('is-ok', Boolean(ok));
        b.disabled = Boolean(ok);
      },
      setAwake(awake) {
        const wasSleeping = b.classList.contains('is-sleeping');
        b.classList.toggle('is-sleeping', !awake);
        b.setAttribute('aria-disabled', String(!awake));
        if (awake && wasSleeping && !isReducedMotion()) {
          b.classList.remove('is-awake');
          void b.offsetWidth;
          b.classList.add('is-awake');
        }
      },
      reset() {
        b.classList.remove('is-busy', 'is-ok');
        b.removeAttribute('aria-busy');
        b.disabled = false;
      }
    };
  }

  /* ── #416 Segmented Tabs with Jelly-Stretch Sliding Thumb & Arrow Keys ── */
  function createSegmentedTabs({ ariaLabel = 'Tabs', tabs = [], activeId = '', extraTabClass = '', onChange = null }) {
    const tl = el('div', 'ux u416-tl');
    tl.setAttribute('role', 'tablist');
    tl.setAttribute('aria-label', ariaLabel);

    const thumb = el('span', 'u416-th');
    thumb.setAttribute('aria-hidden', 'true');
    tl.append(thumb);

    const buttons = [];
    let curIdx = Math.max(0, tabs.findIndex(t => t.id === activeId));

    tabs.forEach((tab, idx) => {
      const b = el('button', ('u416-tab ' + extraTabClass + (idx === curIdx ? ' is-active' : '')).trim(), tab.label);
      b.type = 'button';
      b.dataset.tabId = tab.id;
      b.setAttribute('role', 'tab');
      b.setAttribute('aria-selected', String(idx === curIdx));
      b.tabIndex = idx === curIdx ? 0 : -1;
      b.addEventListener('click', () => selectIndex(idx, false));
      buttons.push(b);
      tl.append(b);
    });

    function positionThumb(idx, dir = 1, instant = false) {
      const t = buttons[idx];
      if (!t || !tl.clientWidth) return;
      if (instant || isReducedMotion()) thumb.style.transition = 'none';
      thumb.style.setProperty('--tl', dir > 0 ? '.62s' : '.38s');
      thumb.style.setProperty('--tr', dir > 0 ? '.38s' : '.62s');
      thumb.style.setProperty('--l', t.offsetLeft + 'px');
      thumb.style.setProperty('--r', Math.max(4, tl.clientWidth - t.offsetLeft - t.offsetWidth) + 'px');
      if (instant || isReducedMotion()) {
        void thumb.offsetWidth;
        thumb.style.transition = '';
      }
    }

    function selectIndex(idx, focusTab = false, silent = false) {
      if (idx < 0 || idx >= buttons.length) return;
      const dir = idx >= curIdx ? 1 : -1;
      curIdx = idx;
      buttons.forEach((b, k) => {
        const on = k === idx;
        b.setAttribute('aria-selected', String(on));
        b.classList.toggle('is-active', on);
        b.tabIndex = on ? 0 : -1;
      });
      positionThumb(idx, dir, false);
      if (focusTab) buttons[idx].focus({ preventScroll: true });
      if (!silent) onChange?.(tabs[idx].id, idx);
    }

    tl.addEventListener('keydown', e => {
      const n = buttons.length;
      const to = e.key === 'ArrowRight' ? curIdx + 1
        : e.key === 'ArrowLeft' ? curIdx - 1
        : e.key === 'Home' ? 0
        : e.key === 'End' ? n - 1
        : null;
      if (to === null) return;
      e.preventDefault();
      selectIndex((to + n) % n, true);
    });

    const ro = typeof ResizeObserver === 'function' ? new ResizeObserver(() => positionThumb(curIdx, 1, true)) : null;
    ro?.observe(tl);
    requestAnimationFrame(() => positionThumb(curIdx, 1, true));

    return {
      el: tl,
      buttons,
      select(id, silent = false) {
        const idx = tabs.findIndex(t => t.id === id);
        if (idx >= 0) selectIndex(idx, false, silent);
      },
      reposition: () => positionThumb(curIdx, 1, true),
      destroy: () => ro?.disconnect()
    };
  }

  /* ── #013 Stretchy Switch (Inline Role=Switch with Drag + Stretch) ── */
  function createStretchySwitch({
    id = nextUid('u013'), label = '', offHint = '', onHint = '',
    checked = false, disabled = false, onChange = null
  } = {}) {
    const root = el('div', 'ux u013-inline' + (checked ? ' is-on' : ''));
    const sw = el('button', 'u013-sw');
    sw.type = 'button';
    sw.id = id;
    sw.disabled = Boolean(disabled);
    sw.setAttribute('role', 'switch');
    sw.setAttribute('aria-checked', String(Boolean(checked)));

    const knob = el('span', 'u013-knob');
    knob.setAttribute('aria-hidden', 'true');
    const svg = svgEl('svg', { viewBox: '0 0 24 24' });
    svg.append(
      svgEl('path', { class: 'u013-x', d: 'M7.5 7.5l9 9M16.5 7.5l-9 9' }),
      svgEl('path', { class: 'u013-v', d: 'M5.5 12.5l4.5 4.5 8.5-9' })
    );
    knob.append(svg);
    sw.append(knob);
    root.append(sw);

    let subEl = null;
    if (label || offHint || onHint) {
      const copy = el('div', 'u013-inline-copy');
      if (label) {
        const labEl = el('span', 'u013-inline-lab', label);
        labEl.id = `${id}-lab`;
        sw.setAttribute('aria-labelledby', labEl.id);
        copy.append(labEl);
      }
      if (offHint || onHint) {
        subEl = el('span', 'u013-inline-sub', checked ? (onHint || offHint) : (offHint || onHint));
        copy.append(subEl);
      }
      root.append(copy);
    }

    let on = Boolean(checked);
    let stretchTimer = 0;
    let drag = null;

    function set(next, emit = true) {
      const val = Boolean(next);
      on = val;
      root.classList.toggle('is-on', val);
      sw.setAttribute('aria-checked', String(val));
      if (subEl) subEl.textContent = val ? (onHint || offHint) : (offHint || onHint);
      if (!isReducedMotion()) {
        root.classList.add('is-stretch');
        clearTimeout(stretchTimer);
        stretchTimer = setTimeout(() => { root.classList.remove('is-stretch'); }, 170);
      }
      if (emit) onChange?.(val);
    }

    sw.addEventListener('pointerdown', e => {
      if (sw.disabled || e.button !== 0) return;
      drag = { x: e.clientX, moved: false, start: on ? 27 : 3 };
      try { sw.setPointerCapture(e.pointerId); } catch (_) {}
    });
    sw.addEventListener('pointermove', e => {
      if (!drag) return;
      const dx = e.clientX - drag.x;
      if (Math.abs(dx) > 4) drag.moved = true;
      if (!drag.moved) return;
      root.classList.add('is-drag');
      const x = Math.max(3, Math.min(27, drag.start + dx));
      knob.style.left = x + 'px';
      knob.style.width = (22 + Math.min(8, Math.abs(dx) * 0.25)) + 'px';
    });
    const finishDrag = () => {
      if (!drag) return;
      const d = drag;
      drag = null;
      root.classList.remove('is-drag');
      const x = parseFloat(knob.style.left || (on ? '27' : '3'));
      knob.style.left = '';
      knob.style.width = '';
      if (d.moved) { set(x > 15, true); sw._skipClick = true; }
    };
    sw.addEventListener('pointerup', finishDrag);
    sw.addEventListener('pointercancel', finishDrag);
    sw.addEventListener('click', () => {
      if (sw.disabled) return;
      if (sw._skipClick) { sw._skipClick = false; return; }
      set(!on, true);
    });

    return {
      el: root,
      button: sw,
      getChecked: () => on,
      setChecked: (v, emit = false) => set(v, emit),
      setDisabled: d => { sw.disabled = Boolean(d); }
    };
  }

  /* ── #439 Shimmer Skeleton Card ── */
  function createSkeletonCard(rows = 3) {
    const card = el('div', 'ux u439-sk-card');
    card.setAttribute('aria-busy', 'true');
    card.append(el('i', 'u439-bone is-title'));
    for (let i = 0; i < rows; i++) {
      card.append(el('i', 'u439-bone ' + (i === rows - 1 ? 'is-mid' : 'is-wide')));
    }
    return card;
  }

  /* ── #408 Account Menu Keyboard Navigation & Spring Stagger ── */
  function enhanceAccountMenu(detailsEl) {
    if (!detailsEl) return;
    detailsEl.classList.add('ux', 'u408-enhanced');
    const summary = detailsEl.querySelector('summary');
    const drop = detailsEl.querySelector('.gh-user-dropdown');
    if (!summary || !drop) return;
    [...drop.children].forEach((child, idx) => child.style.setProperty('--i', String(idx)));
    const items = () => [...drop.querySelectorAll('button:not(:disabled)')];

    summary.addEventListener('keydown', e => {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        detailsEl.open = true;
        const list = items();
        if (list.length) list[e.key === 'ArrowDown' ? 0 : list.length - 1].focus({ preventScroll: true });
      }
    });
    drop.addEventListener('keydown', e => {
      const list = items();
      if (!list.length) return;
      const i = list.indexOf(document.activeElement);
      if (e.key === 'ArrowDown') { e.preventDefault(); list[(i + 1) % list.length].focus(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); list[(i - 1 + list.length) % list.length].focus(); }
      else if (e.key === 'Home') { e.preventDefault(); list[0].focus(); }
      else if (e.key === 'End') { e.preventDefault(); list[list.length - 1].focus(); }
      else if (e.key === 'Escape') { e.preventDefault(); detailsEl.open = false; summary.focus({ preventScroll: true }); }
    });
  }

  /* ── #044 Smooth Accordion / Disclosure with Plus-to-Minus Spring Icon ── */
  function createDisclosure({ id = '', summary = 'More options', open = false, cls = '', onToggle = null } = {}) {
    const det = el('details', ('ux u044-det ' + cls).trim());
    if (id) det.id = id;
    if (open) det.open = true;
    const sum = el('summary', 'u044-sum');
    const pm = el('span', 'u044-pm');
    pm.setAttribute('aria-hidden', 'true');
    sum.append(el('span', 'u044-t', summary), pm);
    const body = el('div', 'u044-cnt');
    det.append(sum, body);
    if (typeof onToggle === 'function') {
      det.addEventListener('toggle', () => onToggle(det.open));
    }
    return {
      el: det,
      summary: sum,
      body,
      setOpen(v) { det.open = Boolean(v); }
    };
  }

  /* ── #062 Shake any card container on authentication error ── */
  function shakeCard(node) {
    if (!node || isReducedMotion()) return;
    node.classList.remove('u062-shake');
    void node.offsetWidth;
    node.classList.add('u062-shake');
  }

  window.EvoLucian = {
    createFloatingField,
    createPasswordStrength,
    createMorphButton,
    createSegmentedTabs,
    createStretchySwitch,
    createSkeletonCard,
    createDisclosure,
    enhanceAccountMenu,
    shakeCard
  };
})();
