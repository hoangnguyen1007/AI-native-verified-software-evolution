/* Layer-0 Typed Pub/Sub Event Bus & Shared Read-Only State (0 DOM, 0 Fetch, strict CSP). */
(() => {
  'use strict';

  const EVENTS = Object.freeze({
    SNAPSHOT_CHANGED: 'evo:snapshot-changed',
    CANVAS_FOCUS: 'evo:canvas-focus',
    CHAT_SEED: 'evo:chat-seed',
    TRACE_OPEN: 'evo:trace-open',
    SOURCE_OPEN: 'evo:source-open',
    REPAIR_SEED: 'evo:repair-seed',
    AUTH_CHANGED: 'evo:auth-changed'
  });

  const CANONICAL_SET = new Set(Object.values(EVENTS));

  function createBus() {
    const listeners = new Map();
    const lastPayloads = new Map();
    let state = {
      graphId: null,
      snapshotId: null,
      contextId: '',
      truthTier: 'NONE',
      contexts: [],
      auth: null,
      activeTab: 'overview'
    };

    function validateType(type) {
      if (typeof type !== 'string' || !type.trim()) {
        throw new TypeError('EvoBus event type must be a non-empty string.');
      }
      return type.trim();
    }

    function cloneState() {
      return {
        ...state,
        contexts: Array.isArray(state.contexts) ? state.contexts.slice() : [],
        auth: state.auth && typeof state.auth === 'object' ? { ...state.auth } : state.auth
      };
    }

    function on(type, fn) {
      const key = validateType(type);
      if (typeof fn !== 'function') {
        throw new TypeError('EvoBus listener must be a function.');
      }
      let bucket = listeners.get(key);
      if (!bucket) {
        bucket = new Set();
        listeners.set(key, bucket);
      }
      bucket.add(fn);
      let active = true;
      return function unsubscribe() {
        if (!active) return;
        active = false;
        const current = listeners.get(key);
        if (!current) return;
        current.delete(fn);
        if (current.size === 0) listeners.delete(key);
      };
    }

    function once(type, fn) {
      if (typeof fn !== 'function') {
        throw new TypeError('EvoBus listener must be a function.');
      }
      let unsub = null;
      unsub = on(type, (payload, eventType) => {
        if (unsub) unsub();
        fn(payload, eventType);
      });
      return unsub;
    }

    function off(type, fn) {
      const key = validateType(type);
      const bucket = listeners.get(key);
      if (!bucket) return;
      bucket.delete(fn);
      if (bucket.size === 0) listeners.delete(key);
    }

    function setState(patch) {
      if (patch && typeof patch === 'object' && !Array.isArray(patch)) {
        const next = { ...state };
        for (const [k, v] of Object.entries(patch)) {
          if (k === '__proto__' || k === 'constructor' || k === 'prototype') continue;
          next[k] = k === 'contexts' && Array.isArray(v) ? v.slice() : v;
        }
        state = next;
      }
      return cloneState();
    }

    function getState() {
      return cloneState();
    }

    function emit(type, payload) {
      const key = validateType(type);
      const eventPayload = payload && typeof payload === 'object' && !Array.isArray(payload)
        ? { ...payload }
        : payload;

      // Mirror canonical state transitions onto bus state so late-mounted modules stay synchronized.
      if (key === EVENTS.SNAPSHOT_CHANGED && eventPayload && typeof eventPayload === 'object') {
        setState({
          graphId: eventPayload.graphId ?? state.graphId,
          snapshotId: eventPayload.snapshotId ?? state.snapshotId,
          contextId: eventPayload.contextId ?? state.contextId,
          truthTier: eventPayload.truthTier ?? state.truthTier,
          contexts: Array.isArray(eventPayload.contexts) ? eventPayload.contexts : state.contexts
        });
      } else if (key === EVENTS.AUTH_CHANGED && eventPayload && typeof eventPayload === 'object') {
        setState({ auth: { ...eventPayload } });
      }

      lastPayloads.set(key, eventPayload);

      const bucket = listeners.get(key);
      if (!bucket || bucket.size === 0) return { delivered: 0, errors: [] };

      const snapshot = [...bucket];
      const errors = [];
      let delivered = 0;
      for (const fn of snapshot) {
        try {
          fn(eventPayload, key);
          delivered++;
        } catch (err) {
          errors.push(err);
          if (typeof console !== 'undefined' && typeof console.error === 'function') {
            console.error('[EvoBus] Listener error on ' + key + ':', err);
          }
        }
      }
      return { delivered, errors };
    }

    function lastEvent(type) {
      const key = validateType(type);
      return lastPayloads.has(key) ? lastPayloads.get(key) : null;
    }

    function listenerCount(type) {
      if (type == null) {
        let total = 0;
        for (const set of listeners.values()) total += set.size;
        return total;
      }
      return listeners.get(validateType(type))?.size || 0;
    }

    function isCanonicalEvent(type) {
      return CANONICAL_SET.has(type);
    }

    function clear() {
      listeners.clear();
      lastPayloads.clear();
      state = {
        graphId: null,
        snapshotId: null,
        contextId: '',
        truthTier: 'NONE',
        contexts: [],
        auth: null,
        activeTab: 'overview'
      };
    }

    return Object.freeze({
      EVENTS,
      on,
      once,
      off,
      emit,
      getState,
      setState,
      lastEvent,
      listenerCount,
      isCanonicalEvent,
      clear,
      createBus
    });
  }

  const bus = createBus();

  if (typeof window !== 'undefined') {
    window.EvoBus = bus;
  }
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = bus;
  }
})();
