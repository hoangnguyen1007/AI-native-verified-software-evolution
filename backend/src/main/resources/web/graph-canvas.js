/* Layer-3 Extracted Multi-Scale Graph Canvas Module (WebGL2/2D + Cytoscape, pure DOM, strict CSP).
 * Preserves 100% of existing Cytoscape functionality while adding Moonshot #3 MACRO/MESO/MICRO
 * super-node drill-down, automatic WebGL2/Cytoscape engine selection, SCC cycle mode, TrustTier
 * badges, and EvoBus bidirectional Chat<->Canvas<->DSM sync.
 */
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

  let cytoscapeLoader = null;

  function loadCytoscape() {
    if (typeof window.cytoscape === 'function') return Promise.resolve(true);
    return cytoscapeLoader ??= new Promise(resolve => {
      const script = document.createElement('script');
      script.src = '/vendor/cytoscape-3.30.2.min.js';
      const unavailable = () => {
        script.remove();
        cytoscapeLoader = null;
        resolve(false);
      };
      script.onload = () => (typeof window.cytoscape === 'function' ? resolve(true) : unavailable());
      script.onerror = unavailable;
      document.head.append(script);
    });
  }

  function layoutConfig(name) {
    if (name === 'concentric') {
      return { name: 'concentric', padding: 36, animate: false, minNodeSpacing: 28, concentric: node => node.degree(), levelWidth: () => 2 };
    }
    if (name === 'circle') {
      return { name: 'circle', padding: 36, animate: false, spacingFactor: 1.2 };
    }
    if (name === 'grid') {
      return { name: 'grid', padding: 36, animate: false, condense: true, spacingFactor: 1.25 };
    }
    return { name: 'breadthfirst', directed: true, padding: 36, spacingFactor: 1.3, animate: false, avoidOverlap: true };
  }

  /**
   * Normalizes both legacy `/queries/visual-graph` `{ nodes, edges, metadata }` and
   * `VisualGraph.toCytoscape()` `{ elements: { nodes: [{data}], edges: [{data}] }, metadata }`
   * into one uniform shape.
   */
  function normalizeVisualGraph(raw) {
    if (!raw || typeof raw !== 'object') {
      return { nodes: [], edges: [], metadata: {} };
    }
    let nodes = Array.isArray(raw.nodes) ? raw.nodes : null;
    let edges = Array.isArray(raw.edges) ? raw.edges : null;
    if (!nodes && raw.elements && Array.isArray(raw.elements.nodes)) {
      nodes = raw.elements.nodes.map(n => (n && n.data ? {
        ...n.data,
        ...(n.position && Number.isFinite(Number(n.position.x)) && Number.isFinite(Number(n.position.y))
          ? { x: Number(n.position.x), y: Number(n.position.y) }
          : {})
      } : n)).filter(Boolean);
    }
    if (!edges && raw.elements && Array.isArray(raw.elements.edges)) {
      edges = raw.elements.edges.map(e => (e && e.data ? { ...e.data } : e)).filter(Boolean);
    }
    nodes = (nodes || []).map(n => ({
      ...n,
      inCycle: Boolean(n.inCycle || n.isCycle || n.isCycleParticipant),
      isCycle: Boolean(n.isCycle || n.inCycle || n.isCycleParticipant),
      trustTier: n.trustTier || n.properties?.trustTier || null
    }));
    edges = (edges || []).map((e, idx) => ({
      ...e,
      id: e.id || `edge:${e.source}->${e.target}:${idx}`,
      inCycle: Boolean(e.inCycle || e.isCycle),
      isCycle: Boolean(e.isCycle || e.inCycle),
      trustTier: e.trustTier || e.properties?.trustTier || null
    }));
    return {
      id: raw.id || raw.graphId || null,
      nodes,
      edges,
      metadata: raw.metadata && typeof raw.metadata === 'object' ? raw.metadata : {}
    };
  }

  /**
   * Pure decision helper for Auto engine selection (tested in workbench-model.test.cjs).
   */
  function chooseEngine(preference, graph, nonCompoundCount, hasCytoscape, hasWebGL) {
    const resolvedLevel = String(graph?.metadata?.resolvedLevel || graph?.metadata?.level || '').toUpperCase();
    const hasSuperNodes = Array.isArray(graph?.nodes) && graph.nodes.some(n => n.kind === 'COMMUNITY_SUPER_NODE');
    if (preference === 'webgl2') return hasWebGL ? 'webgl2' : (hasCytoscape ? 'cytoscape' : 'none');
    if (preference === 'cytoscape') return hasCytoscape ? 'cytoscape' : (hasWebGL ? 'webgl2' : 'none');
    // Auto: WebGL2 for >250 nodes or MACRO super-node views; Cytoscape for <250 micro/compound views.
    if (hasWebGL && (nonCompoundCount > 250 || resolvedLevel === 'MACRO' || hasSuperNodes)) {
      return 'webgl2';
    }
    if (hasCytoscape) return 'cytoscape';
    if (hasWebGL) return 'webgl2';
    return 'none';
  }

  function extractCommunityId(item) {
    if (!item) return null;
    if (item.properties?.communityId != null) return String(item.properties.communityId);
    if (item.communityId != null) return String(item.communityId);
    const id = String(item.id || '');
    if (id.startsWith('community:')) return id.slice('community:'.length);
    if (id.startsWith('compound:community:')) return id.slice('compound:community:'.length);
    return null;
  }

  function isSuperNodeItem(item) {
    if (!item) return false;
    return item.kind === 'COMMUNITY_SUPER_NODE'
      || Boolean(item.properties?.expandable)
      || String(item.id || '').startsWith('community:');
  }

  function isExpandableSuperNodeItem(item) {
    if (!isSuperNodeItem(item)) return false;
    if (item.properties?.expandable === false) return false;
    if (item.properties?.externalGroup) return false;
    if (String(item.id || '') === 'community:external') return false;
    return true;
  }

  function resolveEffectiveScopeForNode(item, fallbackScope, isSpringContext, usedMultiScale) {
    const rawScope = String(item?.scope || '').toUpperCase();
    if (['TYPE', 'PACKAGE', 'MODULE', 'SPRING_COMPONENT', 'ENTITY'].includes(rawScope)) {
      return rawScope;
    }
    const rawKind = String(item?.kind || '').toUpperCase();
    if (rawKind === 'SPRING_COMPONENT' || rawKind === 'BEAN_DEFINITION_CANDIDATE') return 'SPRING_COMPONENT';
    if (rawKind === 'TYPE') return 'TYPE';
    if (rawKind === 'PACKAGE') return 'PACKAGE';
    if (rawKind === 'MODULE') return 'MODULE';
    if (usedMultiScale) return isSpringContext ? 'SPRING_COMPONENT' : 'TYPE';
    return fallbackScope || 'TYPE';
  }

  function readMapTheme(hostNode) {
    const style = getComputedStyle(hostNode);
    const css = (name, fallback) => style.getPropertyValue(name).trim() || fallback;
    const isLight = document.documentElement.dataset.theme === 'light';
    return {
      paper: css('--map-paper', isLight ? '#f2f6f5' : '#101b21'),
      grid: css('--map-grid', isLight ? '#d1dedb' : '#263941'),
      label: css('--map-label', isLight ? '#163035' : '#e9f1f2'),
      labelBg: isLight ? 'rgba(255, 255, 255, 0.92)' : 'rgba(10, 14, 13, 0.86)',
      node: css('--map-node', isLight ? '#dcebe8' : '#21353d'),
      nodeBorder: css('--map-node-border', isLight ? '#647f7c' : '#54838a'),
      group: css('--map-group', isLight ? '#bad8d4' : '#315652'),
      edge: css('--map-edge', isLight ? '#47736b' : '#87bab2'),
      may: css('--map-may', isLight ? '#476ea7' : '#8db4eb'),
      unknown: css('--map-unknown', isLight ? '#91612b' : '#edb777'),
      focus: css('--map-focus', isLight ? '#146c4e' : '#4fd19a'),
      external: css('--map-external', isLight ? '#e6ebf1' : '#263039'),
      cycle: isLight ? '#dc2626' : '#ef4444',
      violation: isLight ? '#b91c1c' : '#f87171',
      bridge: isLight ? '#0891b2' : '#06b6d4',
      drift: isLight ? '#d97706' : '#f59e0b',
      light: isLight
    };
  }

  async function renderView(env, host) {
    const {
      M, state, $, api, safe, btn, card, empty, selectControl, field,
      relationshipKinds, defaultDependencyContext, labelContext, labelContextOptions,
      inspect, inspectEntity, loadTab, download, downloadBlob,
      setRefreshGraphTheme
    } = host;

    const node = card('Dependency map');
    node.classList.add('dependency-map');

    let focus = { ...(state.graphFocus || {}) };
    if (Array.isArray(focus.focusNodes) && focus.focusNodes.length && !focus.seed) {
      focus.seed = focus.focusNodes[0];
    }

    const contexts = [...$('context').options].filter(option => option.value);
    const initialContext = focus.context && contexts.some(option => option.value === focus.context)
      ? focus.context
      : defaultDependencyContext(env);

    const context = selectControl('Graph context', contexts.map(option =>
      [option.value, option.value === env.snapshot ? 'Repository source evidence' : (state.contextLabels?.get(option.value) || option.textContent)]
    ), initialContext);
    context.addEventListener('focus', labelContextOptions);
    context.addEventListener('pointerenter', labelContextOptions);

    const contextField = field('Graph context', context);
    contextField.hidden = env.context !== env.snapshot && initialContext === env.context;

    const scope = selectControl('Graph scope', [
      ['MODULE', 'Modules'], ['PACKAGE', 'Packages'], ['TYPE', 'Classes'],
      ['SPRING_COMPONENT', 'Spring components'], ['ENTITY', 'Source entities']
    ]);
    const groupBy = selectControl('Graph grouping', [
      ['PACKAGE', 'Packages / Modules'], ['COMMUNITY', 'Communities (Leiden)']
    ], focus.groupBy || 'PACKAGE');
    const projection = selectControl('Dependency view', [
      ['BOUNDED_UNION', 'All observed'], ['MUST', 'Required'], ['MAY', 'Conditional'], ['UNKNOWN_OVERLAY', 'Uncertain'],
      ...(focus.realization ? [['REALIZED', 'Recorded configuration']] : [])
    ], focus.projection || 'BOUNDED_UNION');
    const layout = selectControl('Graph layout', [
      ['breadthfirst', 'Hierarchy'], ['concentric', 'Radial'], ['circle', 'Ring'], ['grid', 'Grid']
    ], 'breadthfirst');
    const depth = selectControl('Neighborhood depth', [['1', '1 hop'], ['2', '2 hops'], ['3', '3 hops']]);

    let levelMode = focus.level || (focus.focusNodes?.length ? 'MICRO' : 'AUTO');
    let enginePref = 'auto'; // 'auto' | 'webgl2' | 'cytoscape'
    let expandedCommunities = new Map(); // communityId -> label
    if (Array.isArray(focus.expandCommunities)) {
      for (const cid of focus.expandCommunities) expandedCommunities.set(String(cid), 'Community ' + cid);
    }
    let multiScaleEndpointAvailable = true;
    let legacyControlOverride = Boolean(focus.scope && !focus.focusNodes?.length && !focus.level);

    const clear = btn('Show full map', () => {
      focus = {};
      expandedCommunities.clear();
      levelMode = 'AUTO';
      legacyControlOverride = false;
      if (projection.value !== 'BOUNDED_UNION') projection.value = 'BOUNDED_UNION';
      syncLevelButtons();
      depthField.hidden = clear.hidden = true;
      return load(true);
    });
    const depthField = field('Neighborhood', depth);
    depthField.hidden = clear.hidden = !(focus.seed || focus.focusNodes?.length);

    // Moonshot #3 Level Switcher (AUTO | MACRO | MESO | MICRO) & Engine Switcher (Auto | WebGL2 | Cytoscape)
    const levelGroup = el('div', 'evo-graph-seg-group');
    levelGroup.setAttribute('role', 'group');
    levelGroup.setAttribute('aria-label', 'Graph zoom level');
    levelGroup.append(el('span', 'evo-graph-seg-label', 'Scale'));
    const levelButtons = new Map();
    for (const [lv, lbl] of [['AUTO', 'Auto'], ['MACRO', 'Macro'], ['MESO', 'Meso'], ['MICRO', 'Micro']]) {
      const b = btn(lbl, () => {
        levelMode = lv;
        legacyControlOverride = false;
        if (projection.value !== 'BOUNDED_UNION') projection.value = 'BOUNDED_UNION';
        if (lv === 'MACRO') expandedCommunities.clear();
        syncLevelButtons();
        return load(false);
      }, 'evo-graph-seg-btn' + (levelMode === lv ? ' is-active' : ''));
      b.dataset.level = lv;
      b.setAttribute('aria-pressed', String(levelMode === lv));
      levelButtons.set(lv, b);
      levelGroup.append(b);
    }

    const engineGroup = el('div', 'evo-graph-seg-group');
    engineGroup.setAttribute('role', 'group');
    engineGroup.setAttribute('aria-label', 'Graph renderer engine');
    engineGroup.append(el('span', 'evo-graph-seg-label', 'Engine'));
    const engineButtons = new Map();
    let lastGraphState = null;
    for (const [eng, lbl] of [['auto', 'Auto'], ['webgl2', 'WebGL2'], ['cytoscape', 'Cytoscape']]) {
      const b = btn(lbl, async () => {
        enginePref = eng;
        syncEngineButtons();
        if (lastGraphState) {
          const cyReady = await loadCytoscape();
          if (!env.signal.aborted && lastGraphState.isActive()) {
            renderGraph(lastGraphState.graph, lastGraphState.graphEnv, lastGraphState.request, lastGraphState.isActive, cyReady, lastGraphState.usedMultiScale);
          }
        }
      }, 'evo-graph-seg-btn' + (enginePref === eng ? ' is-active' : ''));
      b.dataset.engine = eng;
      b.setAttribute('aria-pressed', String(enginePref === eng));
      engineButtons.set(eng, b);
      engineGroup.append(b);
    }

    function syncLevelButtons() {
      for (const [lv, b] of levelButtons) {
        const on = lv === levelMode;
        b.classList.toggle('is-active', on);
        b.setAttribute('aria-pressed', String(on));
      }
    }
    function syncEngineButtons() {
      for (const [eng, b] of engineButtons) {
        const on = eng === enginePref;
        b.classList.toggle('is-active', on);
        b.setAttribute('aria-pressed', String(on));
      }
    }

    const multiScaleBar = append(el('div', 'evo-graph-multiscale-bar'), levelGroup, engineGroup);
    const breadcrumbBar = el('div', 'evo-graph-breadcrumbs');
    breadcrumbBar.hidden = true;

    const options = append(el('details', 'graph-options'), el('summary', '', 'View options'),
      append(el('div', 'graph-option-fields'), field('Relationships', projection), field('Layout', layout)));
    const content = el('div', 'graph-result');
    node.append(
      append(el('div', 'graph-query-controls'), contextField, field('Scope', scope), field('Grouping', groupBy), depthField, clear, options),
      multiScaleBar,
      breadcrumbBar,
      content
    );

    const snapshotKinds = ['syntax.declaration', 'syntax.import', 'syntax.heritage', 'CALLS_SERVICE', 'INVOKES_RPC',
      'polyglot.service-call.candidate', 'polyglot.grpc-service-call.candidate', 'polyglot.grpc-contract.candidate'];
    const snapshotPopulation = {
      nodeKinds: ['SOURCE_DOCUMENT', 'SOURCE_DECLARATION', 'SOURCE_IMPORT', 'SOURCE_HERITAGE',
        'CALL_SITE', 'ROUTE_CANDIDATE', 'PROTOBUF_MESSAGE', 'PROTOBUF_SERVICE', 'PROTOBUF_RPC_METHOD']
    };

    let version = 0;
    let canvasObserver = null;
    let expandedDialog = null;
    let pendingLayer = null;
    let activeWebgl = null;
    let activeFocusHandler = null;

    function releaseCanvas() {
      canvasObserver?.disconnect();
      canvasObserver = null;
      if (expandedDialog?.open) expandedDialog.close();
      if (activeWebgl) {
        try { activeWebgl.destroy(); } catch (_) { }
        activeWebgl = null;
      }
      if (state.cy) {
        try { state.cy.destroy(); } catch (_) { }
        state.cy = null;
      }
      activeFocusHandler = null;
      setRefreshGraphTheme(null);
    }

    function renderBreadcrumbs(resolvedLevel) {
      breadcrumbBar.replaceChildren();
      if (expandedCommunities.size === 0 && resolvedLevel !== 'MESO') {
        breadcrumbBar.hidden = true;
        return;
      }
      breadcrumbBar.hidden = false;
      breadcrumbBar.append(
        btn('← Collapse to Macro', () => {
          expandedCommunities.clear();
          levelMode = 'MACRO';
          syncLevelButtons();
          return load(false);
        }, 'evo-graph-crumb-chip')
      );
      for (const [cid, cname] of expandedCommunities.entries()) {
        breadcrumbBar.append(
          btn(`${cname} ×`, () => {
            expandedCommunities.delete(cid);
            if (expandedCommunities.size === 0) levelMode = 'MACRO';
            syncLevelButtons();
            return load(false);
          }, 'evo-graph-crumb-chip')
        );
      }
    }

    async function setScope() {
      const selected = context.value;
      if (selected !== env.snapshot) await labelContext(selected);
      void labelContextOptions();
      if (env.signal.aborted || context.value !== selected) return;
      const spring = state.contextKinds.get(selected) === 'spring.context';
      for (const option of scope.options) {
        option.disabled = selected === env.snapshot ? option.value !== 'ENTITY' : option.value === 'SPRING_COMPONENT' && !spring;
      }
      const lbl = state.contextLabels?.get(selected) || [...$('context').options].find(option => option.value === selected)?.textContent;
      if (lbl && selected !== env.snapshot && context.selectedOptions[0]) context.selectedOptions[0].textContent = lbl;
      const preferred = focus.scope ?? (selected === env.snapshot ? 'ENTITY' : spring ? 'SPRING_COMPONENT' : 'PACKAGE');
      scope.value = [...scope.options].some(option => !option.disabled && option.value === preferred) ? preferred : 'ENTITY';
    }

    function requestFor(selectedContext, layer) {
      const snapshot = selectedContext === env.snapshot;
      return {
        context: selectedContext,
        scope: layer,
        projection: projection.value,
        relationshipKinds: focus.relationshipKinds || (snapshot ? snapshotKinds : relationshipKinds(layer)),
        ...(snapshot && !focus.relationshipKinds ? { population: snapshotPopulation } : {}),
        ...(projection.value === 'REALIZED' ? { realization: focus.realization } : {})
      };
    }

    function shouldTryMultiScale(selectedContext) {
      if (!multiScaleEndpointAvailable) return false;
      if (legacyControlOverride) return false;
      if (selectedContext === env.snapshot) return false;
      if (projection.value !== 'BOUNDED_UNION') return false;
      if (focus.relationshipKinds) return false;
      if (focus.seed && Number(depth.value) > 1 && !focus.focusNodes?.length) return false;
      if (groupBy.value === 'COMMUNITY' && levelMode === 'AUTO' && expandedCommunities.size === 0 && !focus.focusNodes?.length) {
        return false;
      }
      return true;
    }

    async function fetchGraphData(selectedContext, request, preferRelationships) {
      const queryLegacy = req => {
        const vArgs = {
          ...(focus.seed ? { seed: focus.seed, depth: Number(depth.value) } : {}),
          ...(groupBy.value === 'COMMUNITY' ? { groupBy: 'COMMUNITY' } : {})
        };
        return env.query('visual-graph', vArgs, undefined, req);
      };

      if (shouldTryMultiScale(selectedContext)) {
        const focusIds = Array.isArray(focus.focusNodes) && focus.focusNodes.length
          ? focus.focusNodes
          : (focus.seed ? [focus.seed] : []);
        const msPayload = {
          context: selectedContext,
          level: levelMode,
          cycleMode: 'SCC',
          ...(expandedCommunities.size ? { expandCommunities: [...expandedCommunities.keys()] } : {}),
          ...(focusIds.length ? { focusNodes: focusIds } : {})
        };
        try {
          const rawMs = await api('/graphs/' + env.graph + '/visual-graph', msPayload, env.signal);
          const norm = normalizeVisualGraph(rawMs);
          if (norm.nodes.length > 0 || norm.metadata?.resolvedLevel) {
            return { graph: norm, request, usedMultiScale: true };
          }
        } catch (err) {
          if (env.signal.aborted) throw err;
          // Graceful fallback to legacy /queries/visual-graph if endpoint is unavailable or unsupported.
          if (err.status === 404 || err.status === 405 || err.status === 501) {
            multiScaleEndpointAvailable = false;
          }
        }
      }

      let activeReq = request;
      let raw;
      try {
        raw = await queryLegacy(activeReq);
      } catch (error) {
        if (!preferRelationships || activeReq.scope !== 'PACKAGE' || error.code !== 'RESPONSE_TOO_LARGE') throw error;
        activeReq = requestFor(selectedContext, 'MODULE');
        raw = await queryLegacy(activeReq);
      }
      let norm = normalizeVisualGraph(raw);
      if (preferRelationships && activeReq.scope === 'PACKAGE' && !norm.edges.length) {
        const typeRequest = requestFor(selectedContext, 'TYPE');
        const types = await queryLegacy(typeRequest).catch(error => {
          if (norm.nodes.length && error.code === 'RESPONSE_TOO_LARGE') return null;
          throw error;
        });
        if (types) {
          const normTypes = normalizeVisualGraph(types);
          if (normTypes.edges.length || (!norm.nodes.length && normTypes.nodes.length)) {
            norm = normTypes;
            activeReq = typeRequest;
          }
        }
      }
      return { graph: norm, request: activeReq, usedMultiScale: false };
    }

    async function load(preferRelationships = false) {
      const current = ++version;
      const isActive = () => !env.signal.aborted && current === version;
      releaseCanvas();
      content.replaceChildren(empty('Loading dependency map', 'Reading relationships from the selected analysis context.'));
      const selectedContext = context.value;
      const baseReq = requestFor(selectedContext, scope.value);
      try {
        const fetched = await fetchGraphData(selectedContext, baseReq, preferRelationships);
        if (!isActive()) return;
        const graph = fetched.graph;
        const usedMultiScale = Boolean(fetched.usedMultiScale);
        let request = fetched.request;
        const resolvedLevel = String(graph.metadata?.resolvedLevel || graph.metadata?.level || '').toUpperCase();
        if (usedMultiScale && resolvedLevel === 'MICRO') {
          const concreteNode = graph.nodes.find(n => !String(n.id || '').startsWith('compound:') && !isSuperNodeItem(n));
          const isSpringCtx = state.contextKinds.get(selectedContext) === 'spring.context';
          const concreteScope = resolveEffectiveScopeForNode(concreteNode, request.scope, isSpringCtx, true);
          if ([...scope.options].some(o => !o.disabled && o.value === concreteScope)) {
            scope.value = concreteScope;
            request = requestFor(selectedContext, concreteScope);
          }
        } else {
          scope.value = request.scope;
        }
        const graphEnv = { ...env, context: selectedContext, request: { ...env.request, ...request } };
        renderBreadcrumbs(resolvedLevel);
        if (!graph.nodes.length) {
          content.replaceChildren(
            empty(graph.metadata?.isTruncated || graph.metadata?.truncated ? 'The map reached its query limit' : 'No nodes in this scope',
              'Try another scope or browse the captured repository structure.'),
            btn('Browse structure', () => loadTab('structure'))
          );
          return;
        }
        lastGraphState = { graph, graphEnv, request, isActive, usedMultiScale };
        const nonCompoundCount = graph.nodes.filter(n => !String(n.id).startsWith('compound:')).length;
        const preliminaryEngine = chooseEngine(enginePref, graph, nonCompoundCount, true, Boolean(window.EvoWebGLGraph));
        const rendererAvailable = preliminaryEngine === 'cytoscape' ? await loadCytoscape() : (typeof window.cytoscape === 'function');
        if (!isActive()) return;
        renderGraph(graph, graphEnv, request, isActive, rendererAvailable, usedMultiScale);
      } catch (error) {
        if (isActive()) {
          content.replaceChildren(empty('Could not load the map', error.message), btn('Retry graph', () => load(preferRelationships)));
        }
      }
    }

    function renderGraph(graph, graphEnv, request, active, cytoscapeAvailable, usedMultiScale = false) {
      releaseCanvas();
      const selectedContext = graphEnv.context;
      const isSpringContext = state.contextKinds.get(selectedContext) === 'spring.context';
      const theme = readMapTheme(node);
      const color = name => getComputedStyle(node).getPropertyValue(name).trim();
      const compound = item => String(item.id || '').startsWith('compound:');
      const isSuperNode = item => isSuperNodeItem(item);
      const isExpandableSuperNode = item => isExpandableSuperNodeItem(item);
      const effectiveQueryRequestFor = item => {
        const effScope = resolveEffectiveScopeForNode(item, request.scope, isSpringContext, usedMultiScale);
        return { ...request, scope: effScope, relationshipKinds: relationshipKinds(effScope) };
      };
      const fullLabel = item => {
        if (!item) return '';
        const prefix = 'compound:module:';
        const mod = String(item.id || '').startsWith(prefix) ? state.moduleLabels?.get(item.id.slice(prefix.length)) : null;
        return mod === '.' ? 'Root module' : mod || item.label || (item.kind === 'PACKAGE' ? '(default package)' : M.shortDigest(item.id));
      };

      const nodes = graph.nodes.filter(item => !compound(item));
      const byId = new Map(graph.nodes.map(item => [item.id, item]));
      const degree = new Map();
      for (const { source, target } of graph.edges) {
        degree.set(source, (degree.get(source) || 0) + 1);
        degree.set(target, (degree.get(target) || 0) + 1);
      }
      const ranked = [...nodes].sort((a, b) => (degree.get(b.id) || 0) - (degree.get(a.id) || 0) || String(a.id).localeCompare(String(b.id)));
      const hasWebGL = Boolean(window.EvoWebGLGraph?.create);
      const chosenEngine = chooseEngine(enginePref, graph, nodes.length, cytoscapeAvailable, hasWebGL);

      const isCommunityMode = chosenEngine === 'cytoscape'
        && groupBy.value === 'COMMUNITY'
        && graph.nodes.some(item => String(item.id).startsWith('compound:community:'));
      const communityCompounds = isCommunityMode ? graph.nodes.filter(item => String(item.id).startsWith('compound:community:')) : [];
      const externalCompound = isCommunityMode ? graph.nodes.find(item => item.id === 'compound:external') : null;
      const collapsedCommunities = new Set();
      if (isCommunityMode && nodes.length > 120 && communityCompounds.length >= 2) {
        for (const c of communityCompounds) collapsedCommunities.add(c.id);
        if (externalCompound) collapsedCommunities.add(externalCompound.id);
      }

      const visible = (chosenEngine === 'webgl2' || isCommunityMode) ? ranked : ranked.slice(0, 220);
      const included = new Set(visible.map(item => item.id));
      for (const item of visible) {
        for (let p = item.parent; byId.has(p) && !included.has(p); p = byId.get(p).parent) included.add(p);
      }
      const edges = (chosenEngine === 'webgl2' || isCommunityMode)
        ? graph.edges
        : graph.edges.filter(edge => included.has(edge.source) && included.has(edge.target)).slice(0, 700);
      const limited = chosenEngine === 'cytoscape' && !isCommunityMode && (visible.length < nodes.length || edges.length < graph.edges.length);

      const resolvedLevel = String(graph.metadata?.resolvedLevel || graph.metadata?.level || '').toUpperCase();
      const cycleMode = String(graph.metadata?.cycleMode || 'SCC').toUpperCase();
      const sccSummary = graph.metadata?.sccSummary || null;
      const isTruncated = Boolean(graph.metadata?.truncated || graph.metadata?.isTruncated);

      const countsText = resolvedLevel === 'MACRO'
        ? `${nodes.length} community super-nodes · ${edges.length} inter-community edges`
          + (graph.metadata?.totalProjectNodes ? ` (representing ${Number(graph.metadata.totalProjectNodes).toLocaleString()} entities)` : '')
        : isCommunityMode
          ? `${communityCompounds.length} Leiden communities · ${nodes.length} nodes`
            + (Number(graph.metadata?.externalNodeCount || 0) > 0 ? ` (${graph.metadata.externalNodeCount} external)` : '')
            + ` · ${graph.edges.length} edges · Q=${Number(graph.metadata?.communityModularity ?? 0).toFixed(3)} · ${graph.metadata?.misplacedCount ?? 0} misplaced`
          : visible.length.toLocaleString() + ' nodes · ' + edges.length.toLocaleString() + ' relationships' +
            (limited ? ' shown of ' + nodes.length.toLocaleString() + ' nodes and ' + graph.edges.length.toLocaleString() + ' relationships returned' : '');

      const counts = el('p', 'graph-counts', countsText);
      counts.setAttribute('role', 'status');

      // Honest metadata badges strip (Level, Engine, CycleMode: SCC, Truncated, TrustTier)
      const metaStrip = el('div', 'evo-graph-meta-strip');
      if (resolvedLevel) {
        metaStrip.append(el('span', 'evo-graph-pill is-accent', 'Level: ' + resolvedLevel));
      }
      const engineBadge = el('span', 'evo-graph-pill', chosenEngine === 'webgl2' ? 'Renderer: WebGL2' : 'Renderer: Cytoscape');
      metaStrip.append(engineBadge);
      metaStrip.append(el('span', 'evo-graph-pill',
        sccSummary && Number(sccSummary.cyclicBlockCount || 0) > 0
          ? `Cycles: ${cycleMode} (${sccSummary.cyclicBlockCount} SCC blocks)`
          : `Cycles: ${cycleMode}`
      ));
      if (isTruncated) {
        const capInfo = graph.metadata?.cappedNodeCount ? ` (${Number(graph.metadata.cappedNodeCount).toLocaleString()} omitted)` : '';
        metaStrip.append(el('span', 'evo-graph-pill is-warn', 'Truncated' + capInfo));
      }
      if (window.EvoTrust) {
        const dominantTier = M.isPreliminaryTier?.(state.truthTier)
          ? 'SOURCE_SYNTAX'
          : (nodes[0] ? window.EvoTrust.inferFromRecord(nodes[0]) : 'COMPILER');
        metaStrip.append(window.EvoTrust.renderBadge(dominantTier, { compact: true }));
      }

      const status = isTruncated ? el('p', 'graph-message', 'The query reached its bound. Expand a community or focus specific nodes for detail.') : null;
      const edgesMissing = !graph.edges.length ? el('p', 'graph-message', 'This scope has nodes and no recorded relationships for the selected view.') : null;

      const shortCounts = new Map();
      const shortName = item => fullLabel(item).split('.').at(-1);
      for (const item of visible) shortCounts.set(shortName(item), (shortCounts.get(shortName(item)) || 0) + 1);
      const label = item => {
        const text = fullLabel(item);
        if (compound(item) || isSuperNode(item) || !['TYPE', 'PACKAGE'].includes(item.kind)) return text;
        if (item.origin === 'PROJECT' && shortCounts.get(shortName(item)) === 1) return shortName(item);
        return text.split('.').slice(-2).join('.');
      };

      const inspectNode = item => {
        if (!item) return;
        if (isSuperNode(item)) {
          const canExpand = isExpandableSuperNode(item);
          const kindLabel = item.properties?.externalGroup ? 'EXTERNAL DEPENDENCY GROUP' : 'COMMUNITY SUPER-NODE';
          const actions = canExpand
            ? [{ label: 'Expand community (Meso)', run: () => { $('inspector')?.close?.(); return expandSuperNode(item); } }]
            : [];
          return inspect(fullLabel(item), kindLabel, {
            id: item.id,
            label: fullLabel(item),
            kind: item.kind,
            trustTier: item.trustTier || item.properties?.trustTier || null,
            memberCount: item.properties?.memberCount ?? 0,
            internalEdges: item.properties?.internalEdges ?? 0,
            cohesion: item.properties?.cohesion ?? null,
            externalGroup: Boolean(item.properties?.externalGroup),
            expandable: canExpand,
            sampleMembers: item.properties?.sampleMembers || [],
            properties: item.properties || {}
          }, actions);
        }
        return inspectEntity({ ...item, label: fullLabel(item), identity: item.id }, graphEnv);
      };
      const details = btn('Download graph JSON', () => download(graph, 'dependency-graph.json'));
      const nodeList = () => append(el('div', 'graph-node-list'), ...visible.slice(0, 24).map(item =>
        btn(fullLabel(item), () => inspectNode(item), 'graph-node-link')));

      content.replaceChildren(counts, metaStrip, ...[status, edgesMissing].filter(Boolean));
      if (chosenEngine === 'none') {
        content.append(empty('Interactive view unavailable', 'Inspect the captured nodes below or download the projection.'),
          nodeList(), details, btn('Retry graph', () => load()));
        return;
      }

      const canvas = el('div', 'graph-canvas evo-graph-canvas-host');
      canvas.setAttribute('aria-label', 'Interactive dependency graph');
      canvas.tabIndex = 0;

      const dropdownNodes = visible.slice(0, 500);
      const selected = selectControl('Graph node', [['', 'Choose a node'], ...dropdownNodes.map(item => [item.id, fullLabel(item)])], '');
      const filter = el('input');
      filter.type = 'search';
      filter.placeholder = 'Highlight a node';
      filter.setAttribute('aria-label', 'Filter canvas nodes');
      const readout = el('p', 'graph-selection', resolvedLevel === 'MACRO'
        ? 'Click a super-node to inspect, or double-click to expand into Meso view.'
        : 'Select a node to see where its arrows lead.');
      readout.setAttribute('role', 'status');
      const railTrustHost = el('div', 'evo-graph-rail-trust');

      const expandSuperNode = item => {
        if (!isExpandableSuperNode(item)) return;
        const cid = extractCommunityId(item);
        if (!cid) return;
        expandedCommunities.set(cid, fullLabel(item));
        legacyControlOverride = false;
        levelMode = 'MESO';
        syncLevelButtons();
        return load(false);
      };

      const spotlight = btn('Focus & expand', () => {
        const item = byId.get(selected.value);
        if (item && isSuperNode(item)) {
          return isExpandableSuperNode(item) ? expandSuperNode(item) : inspectNode(item);
        }
        focus = { ...focus, seed: selected.value, focusNodes: [selected.value] };
        legacyControlOverride = false;
        if (resolvedLevel === 'MACRO' || resolvedLevel === 'MESO') {
          levelMode = 'MICRO';
          syncLevelButtons();
        }
        depthField.hidden = clear.hidden = false;
        return load(false);
      });
      const inspectButton = btn('Node details', () => inspectNode(byId.get(selected.value)));
      const blastButton = btn('Blast radius', async () => {
        const id = selected.value;
        const item = byId.get(id);
        if (!id || isSuperNode(item)) return;
        const impactReq = effectiveQueryRequestFor(item);
        const payload = M.buildImpactQuery
          ? M.buildImpactQuery(graphEnv.context, id, impactReq)
          : { request: { ...env.request, ...impactReq, direction: 'INCOMING' }, arguments: { identity: id } };
        const impact = await api('/graphs/' + env.graph + '/queries/impact', payload, env.signal);
        if (!active()) return;
        const affected = new Set([id]);
        for (const entry of impact.items || []) {
          if (Array.isArray(entry.vertices)) entry.vertices.forEach(v => affected.add(v));
          else if (entry.target || entry.identity) affected.add(entry.target || entry.identity);
        }
        highlightSubset(affected, null);
        const impactedCount = Math.max(0, affected.size - 1);
        readout.replaceChildren(el('strong', '', fullLabel(byId.get(id))),
          el('span', '', `${impactedCount} upstream impacted node${impactedCount === 1 ? '' : 's'} across ${impact.items?.length || 0} path${(impact.items?.length || 0) === 1 ? '' : 's'}`));
      });
      const askAiNode = btn('Ask AI about selection', () => {
        const item = byId.get(selected.value);
        const targetName = item ? fullLabel(item) : selected.value;
        const promptText = item && isSuperNode(item)
          ? `Explain the responsibilities, cohesion, and cross-community coupling of community \`${targetName}\` (${item.properties?.memberCount || '?'} members).`
          : `Explain the responsibilities, incoming/outgoing dependencies, and refactoring risk of \`${targetName}\`.`;
        if (window.EvoBus) {
          window.EvoBus.emit('evo:chat-seed', {
            prompt: promptText,
            focusNodeId: selected.value || undefined,
            contextId: selectedContext,
            mode: 'EXPLORE'
          });
        } else {
          state.chatSeedPrompt = promptText;
          return loadTab('architecture-chat');
        }
      });
      const traceNode = btn('Vertical Trace', () => {
        const item = byId.get(selected.value);
        if (!item) return;
        window.EvoBus?.emit('evo:trace-open', {
          seedId: item.id,
          seedKind: item.kind,
          includeAdvisory: true
        });
      });
      spotlight.disabled = inspectButton.disabled = blastButton.disabled = askAiNode.disabled = traceNode.disabled = true;

      const hubs = append(el('div', 'graph-node-list'), ...ranked.filter(item => degree.has(item.id)).slice(0, 4).map(item =>
        btn(fullLabel(item), () => selectNode(item.id), 'graph-node-link')));
      const rail = append(el('aside', 'graph-rail'), field('Node', selected), railTrustHost, readout,
        append(el('div', 'graph-node-actions'), inspectButton, spotlight, blastButton, traceNode, askAiNode),
        hubs.children.length ? append(el('div', 'graph-hubs'), el('h3', '', 'Most connected'), hubs) : null);

      let cy = null;
      let webgl = null;
      let previousViewport = null;

      const doZoom = factor => {
        if (webgl) {
          if (factor > 1) webgl.zoomIn();
          else webgl.zoomOut();
        } else if (cy) {
          cy.zoom({ level: cy.zoom() * factor, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
        }
      };
      const doFit = () => {
        if (webgl) webgl.fit(48);
        else if (cy) cy.fit(undefined, 34);
      };

      const zoom = append(el('div', 'graph-zoom'),
        btn('−', () => doZoom(0.8)), btn('+', () => doZoom(1.25)),
        btn('Fit', () => doFit()));
      zoom.children[0].setAttribute('aria-label', 'Zoom out');
      zoom.children[1].setAttribute('aria-label', 'Zoom in');
      zoom.children[2].setAttribute('aria-label', 'Fit graph');

      const filterChips = el('div', 'graph-filter-chips');
      const activeFilter = { kind: 'ALL' };
      const applyChipFilter = kind => {
        activeFilter.kind = kind;
        for (const ch of filterChips.children) ch.classList.toggle('is-active', ch.dataset.filter === kind);
        resetHighlight();
        if (kind === 'ALL') return;
        if (webgl) {
          const matchedNodes = new Set();
          const matchedEdges = new Set();
          if (kind === 'EXTERNAL') {
            for (const n of visible) if (n.origin && n.origin !== 'PROJECT') matchedNodes.add(n.id);
          } else {
            for (const e of edges) {
              if (e.classification === kind) {
                matchedEdges.add(e.id);
                matchedNodes.add(e.source);
                matchedNodes.add(e.target);
              }
            }
          }
          webgl.setHighlight({ nodeIds: [...matchedNodes], edgeIds: [...matchedEdges], dimOthers: true });
          return;
        }
        if (!cy) return;
        let matched;
        if (kind === 'EXTERNAL') matched = cy.nodes().filter(n => n.data('external'));
        else matched = cy.edges().filter(e => e.data('classification') === kind).connectedNodes().union(cy.edges().filter(e => e.data('classification') === kind));
        cy.elements().not(':parent').not(matched).addClass('dimmed');
        matched.addClass('map-active');
      };
      for (const [fk, fl] of [['ALL', 'All'], ['MUST', 'Required'], ['MAY', 'Conditional'], ['UNKNOWN', 'Uncertain'], ['EXTERNAL', 'External']]) {
        const ch = btn(fl, () => applyChipFilter(fk), 'graph-chip' + (fk === 'ALL' ? ' is-active' : ''));
        ch.dataset.filter = fk;
        filterChips.append(ch);
      }

      const layerPills = el('div', 'evo-layer-pills');
      const layerState = { drift: false, bridges: false, tangles: false };
      const applyLayers = () => {
        const anyOn = layerState.drift || layerState.bridges || layerState.tangles;
        if (webgl) {
          if (!anyOn) {
            if (activeFilter.kind === 'ALL') webgl.clearHighlight();
            return;
          }
          const matchedNodes = new Set();
          const matchedEdges = new Set();
          if (layerState.drift) {
            for (const n of visible) if (n.properties?.misplaced || Number(n.properties?.misplacedCount || 0) > 0) matchedNodes.add(n.id);
          }
          if (layerState.bridges) {
            for (const e of edges) {
              if (e.properties?.isBridge) {
                matchedEdges.add(e.id);
                matchedNodes.add(e.source);
                matchedNodes.add(e.target);
              }
            }
          }
          if (layerState.tangles) {
            for (const n of visible) if (n.inCycle || n.isCycle || Number(n.properties?.inCycleCount || 0) > 0) matchedNodes.add(n.id);
            for (const e of edges) if (e.inCycle || e.isCycle) matchedEdges.add(e.id);
          }
          webgl.setHighlight({ nodeIds: [...matchedNodes], edgeIds: [...matchedEdges], dimOthers: true });
          return;
        }
        if (!cy) return;
        cy.elements().removeClass('evo-layer-drift evo-layer-bridge evo-layer-tangle');
        if (!anyOn) {
          if (activeFilter.kind === 'ALL') resetHighlight();
          return;
        }
        resetHighlight();
        let focusEls = cy.collection();
        if (layerState.drift) {
          const drifted = cy.nodes().filter(n =>
            Boolean(n.data('misplaced'))
            || (Boolean(n.data('isSuperNode')) && Number(n.data('misplacedCount') || 0) > 0)
          );
          drifted.addClass('evo-layer-drift');
          focusEls = focusEls.union(drifted);
        }
        if (layerState.bridges) {
          const bridgeEdges = cy.edges().filter(e => Boolean(e.data('isBridge')));
          bridgeEdges.addClass('evo-layer-bridge');
          focusEls = focusEls.union(bridgeEdges).union(bridgeEdges.connectedNodes());
        }
        if (layerState.tangles) {
          const cycNodes = cy.nodes().filter(n =>
            Boolean(n.data('inCycle'))
            || (Boolean(n.data('isSuperNode')) && Number(n.data('inCycleCount') || 0) > 0)
          );
          const cycEdges = cy.edges().filter(e => Boolean(e.data('inCycle')));
          cycNodes.addClass('evo-layer-tangle');
          cycEdges.addClass('evo-layer-tangle');
          focusEls = focusEls.union(cycNodes).union(cycEdges);
        }
        if (focusEls.length) {
          cy.elements().not(':parent').not(focusEls).addClass('dimmed');
        }
      };

      const toggleLayer = (key, buttonEl) => {
        if ((key === 'drift' || key === 'bridges') && chosenEngine === 'cytoscape' && groupBy.value !== 'COMMUNITY') {
          groupBy.value = 'COMMUNITY';
          pendingLayer = key;
          void load();
          return;
        }
        layerState[key] = !layerState[key];
        buttonEl.classList.toggle('is-active', layerState[key]);
        buttonEl.setAttribute('aria-pressed', String(layerState[key]));
        applyLayers();
      };

      const driftPill = btn(
        isCommunityMode ? `Drift (${graph.metadata?.misplacedCount ?? 0})` : 'Drift (Communities)',
        () => toggleLayer('drift', driftPill),
        'evo-layer-pill is-drift'
      );
      const bridgePill = btn(
        isCommunityMode ? `Bridges (${graph.metadata?.bridgeEdgeCount ?? 0})` : 'Bridges (Communities)',
        () => toggleLayer('bridges', bridgePill),
        'evo-layer-pill is-bridges'
      );
      const tanglePill = btn('Tangles', () => toggleLayer('tangles', tanglePill), 'evo-layer-pill is-tangles');
      driftPill.setAttribute('aria-pressed', 'false');
      bridgePill.setAttribute('aria-pressed', 'false');
      tanglePill.setAttribute('aria-pressed', 'false');
      layerPills.append(driftPill, bridgePill, tanglePill);

      let rebuildCommunityView = null;
      if (isCommunityMode) {
        const collapseAllBtn = btn('Collapse all', () => {
          communityCompounds.forEach(c => collapsedCommunities.add(c.id));
          if (externalCompound) collapsedCommunities.add(externalCompound.id);
          rebuildCommunityView?.();
        }, 'evo-layer-pill');
        const expandAllBtn = btn('Expand all', () => {
          collapsedCommunities.clear();
          rebuildCommunityView?.();
        }, 'evo-layer-pill');
        layerPills.append(collapseAllBtn, expandAllBtn);
      }

      const exportPngBtn = btn('Export PNG', () => {
        const bg = color('--map-paper') || theme.paper;
        if (webgl) webgl.exportPng('evo-dependency-map.png', bg);
        else if (cy) window.EvoCommunityAdvisory?.exportCytoscapePng?.(cy, 'evo-dependency-map.png', bg);
      }, 'evo-layer-pill');
      const exportSvgBtn = btn('Export SVG', () => {
        if (!cy) return;
        window.EvoCommunityAdvisory?.exportCytoscapeSvg?.(cy, 'evo-dependency-map.svg', {
          bg: color('--map-paper') || '#0b1120',
          label: color('--map-label') || '#e2e8f0',
          node: color('--map-node') || '#1e293b',
          border: color('--map-node-border') || '#475569',
          edge: color('--map-edge') || '#64748b',
          group: color('--map-group') || '#1e293b'
        });
      }, 'evo-layer-pill');
      exportSvgBtn.hidden = chosenEngine === 'webgl2';
      layerPills.append(exportPngBtn, exportSvgBtn);

      const hoverBar = el('div', 'evo-graph-hover-bar', 'Hover any node to preview trust tier and degree.');
      const dialog = el('dialog', 'map-fullscreen');
      dialog.setAttribute('aria-label', 'Expanded dependency map');
      const expand = btn('Expand map', () => {
        previousViewport = webgl ? webgl.getViewport() : (cy ? { zoom: cy.zoom(), pan: cy.pan() } : null);
        dialog.append(board);
        dialog.showModal();
        if (webgl) { webgl.resize(); webgl.fit(48); }
        else if (cy) { cy.resize(); cy.fit(undefined, 34); }
      }, 'button graph-fullscreen-button');
      expand.title = 'Expand map (F)';
      zoom.append(expand);

      const stage = append(
        el('div', 'graph-stage'),
        filterChips,
        layerPills,
        append(el('div', 'graph-stage-controls'), filter, zoom),
        canvas,
        hoverBar,
        append(el('div', 'map-legend'),
          el('span', 'legend-must', 'Required'), el('span', 'legend-may', 'Conditional'), el('span', 'legend-unknown', 'Uncertain'),
          M.isPreliminaryTier?.(state.truthTier) ? el('span', 'legend-may', 'Preliminary imports') : null,
          visible.some(item => item.origin && item.origin !== 'PROJECT') ? el('span', 'legend-external', 'External') : null)
      );
      const board = append(el('div', 'graph-board'), stage, rail);
      dialog.append(append(el('header', 'map-fullscreen-header'), el('h3', '', 'Dependency map'), btn('Close map', () => dialog.close())));
      content.append(board, dialog);

      const preliminaryGraph = Boolean(M.isPreliminaryTier?.(state.truthTier));

      function updateRailForNode(item, inDeg, outDeg) {
        railTrustHost.replaceChildren();
        if (item && window.EvoTrust) {
          const tier = window.EvoTrust.inferFromRecord(item);
          railTrustHost.append(window.EvoTrust.renderBadge(tier, {
            compact: true,
            classification: item.classification || item.properties?.classification
          }));
        }
        if (item && isSuperNode(item)) {
          const mc = Number(item.properties?.memberCount || 0);
          const coh = item.properties?.cohesion != null ? ` · cohesion ${Number(item.properties.cohesion).toFixed(2)}` : '';
          const canExpand = isExpandableSuperNode(item);
          readout.replaceChildren(
            el('strong', '', fullLabel(item)),
            el('span', '', canExpand
              ? `${mc} members${coh} · ${inDeg} in · ${outDeg} out · double-click to expand`
              : `${mc} external members · ${inDeg} in · ${outDeg} out · collapsed group`)
          );
          spotlight.textContent = canExpand ? 'Expand community' : 'Inspect group';
        } else if (item) {
          readout.replaceChildren(
            el('strong', '', fullLabel(item)),
            el('span', '', `${inDeg} incoming · ${outDeg} outgoing on this map`)
          );
          spotlight.textContent = 'Focus & expand';
        }
      }

      function resetHighlight() {
        if (webgl) webgl.clearHighlight();
        if (cy) cy.elements().removeClass('dimmed map-active path-highlight');
      }

      function highlightSubset(nodeIdSet, edgePairs) {
        resetHighlight();
        if (webgl) {
          const eIds = [];
          for (const e of edges) {
            const match = edgePairs
              ? edgePairs.some(p => p.source === e.source && p.target === e.target)
              : (nodeIdSet.has(e.source) && nodeIdSet.has(e.target));
            if (match) eIds.push(e.id);
          }
          webgl.setHighlight({ nodeIds: [...nodeIdSet], edgeIds: eIds, dimOthers: true, pulse: true });
          return;
        }
        if (!cy) return;
        cy.elements().not(':parent').addClass('dimmed');
        cy.nodes().filter(n => nodeIdSet.has(n.id())).removeClass('dimmed').addClass('path-highlight');
        cy.edges().filter(e => edgePairs
          ? edgePairs.some(p => p.source === e.source().id() && p.target === e.target().id())
          : (nodeIdSet.has(e.source().id()) && nodeIdSet.has(e.target().id()))
        ).removeClass('dimmed').addClass('path-highlight');
      }

      function selectNode(id, opts = {}) {
        const item = byId.get(id);
        if (!item || compound(item)) return false;
        if (![...selected.options].some(o => o.value === id)) {
          const opt = el('option', '', fullLabel(item));
          opt.value = id;
          selected.append(opt);
        }
        selected.value = id;
        const superItem = isSuperNode(item);
        spotlight.disabled = superItem && !isExpandableSuperNode(item);
        inspectButton.disabled = askAiNode.disabled = false;
        blastButton.disabled = traceNode.disabled = superItem;
        filter.value = '';
        if (webgl) {
          let inD = 0, outD = 0;
          for (const e of edges) {
            if (e.target === id) inD++;
            if (e.source === id) outD++;
          }
          webgl.focusNodes([id], { pulse: Boolean(opts.pulse), dimOthers: true, select: true });
          updateRailForNode(item, inD, outD);
          return true;
        }
        if (cy) {
          const picked = cy.getElementById(id);
          if (!picked.length) return false;
          resetHighlight();
          const neighborhood = picked.closedNeighborhood();
          cy.elements().not(neighborhood).not(':parent').addClass('dimmed');
          neighborhood.addClass('map-active');
          if (opts.pulse) {
            picked.addClass('path-highlight');
            try { cy.animate({ center: { eles: picked } }, { duration: 260 }); } catch (_) { }
          }
          updateRailForNode(item, picked.indegree(false), picked.outdegree(false));
          return true;
        }
        return false;
      }

      // Mount either EvoWebGLGraph or Cytoscape
      if (chosenEngine === 'webgl2') {
        webgl = window.EvoWebGLGraph.create(canvas, {
          theme,
          colorBy: 'community',
          onNodeHover: raw => {
            if (!raw) {
              hoverBar.textContent = 'Hover any node to preview trust tier and degree.';
              return;
            }
            const tier = window.EvoTrust ? window.EvoTrust.inferFromRecord(raw) : (raw.trustTier || 'SOURCE_SYNTAX');
            const deg = degree.get(raw.id) || 0;
            hoverBar.replaceChildren(
              el('strong', '', fullLabel(raw)),
              window.EvoTrust ? window.EvoTrust.renderBadge(tier, { compact: true }) : null,
              el('span', '', isExpandableSuperNode(raw)
                ? `${raw.properties?.memberCount || 0} members · double-click to expand`
                : isSuperNode(raw)
                  ? `${raw.properties?.memberCount || 0} external members · double-click to inspect`
                  : `${deg} edge${deg === 1 ? '' : 's'}${raw.inCycle ? ' · in SCC cycle' : ''}`)
            );
          },
          onNodeClick: raw => {
            if (raw) selectNode(raw.id);
          },
          onNodeDoubleClick: raw => {
            if (!raw) return;
            if (isExpandableSuperNode(raw)) void safe(() => expandSuperNode(raw));
            else void safe(() => inspectNode(raw));
          },
          onBackgroundClick: () => {
            selected.value = '';
            spotlight.disabled = inspectButton.disabled = blastButton.disabled = askAiNode.disabled = traceNode.disabled = true;
            railTrustHost.replaceChildren();
            resetHighlight();
          }
        });
        activeWebgl = webgl;
        engineBadge.textContent = webgl.getRendererKind() === 'webgl2' ? 'Renderer: WebGL2 GPU' : 'Renderer: Canvas 2D';
        webgl.setData({
          nodes: visible.map(item => ({
            ...item,
            shortLabel: label(item),
            fullLabel: fullLabel(item),
            isSuperNode: isSuperNode(item),
            expandable: isExpandableSuperNode(item),
            externalGroup: Boolean(item.properties?.externalGroup),
            memberCount: Number(item.properties?.memberCount || 0),
            communityId: extractCommunityId(item) || item.parent || item.kind,
            x: item.x ?? item.properties?.x,
            y: item.y ?? item.properties?.y
          })),
          edges: edges.map(item => ({
            ...item,
            preliminary: Boolean(preliminaryGraph || item.derivation === 'SYNTAX_IMPORT_PREVIEW')
          }))
        });
        setRefreshGraphTheme(() => webgl?.setTheme(readMapTheme(node)));
      } else {
        const graphStyle = () => [
          {
            selector: 'node', style: {
              label: 'data(label)', 'font-size': 12, 'font-weight': 600, color: color('--map-label'),
              shape: 'round-rectangle', 'background-color': color('--map-node'), 'border-color': color('--map-node-border'), 'border-width': 1.2,
              width: 'data(nodeWidth)', height: 34, 'text-valign': 'center', 'text-halign': 'center', 'text-wrap': 'ellipsis', 'text-max-width': 115
            }
          },
          {
            selector: ':parent', style: {
              'background-color': color('--map-group'), 'background-opacity': .22, 'border-width': 1,
              'border-color': color('--map-node-border'), padding: 20, 'text-valign': 'top', 'text-halign': 'center', 'text-max-width': 165
            }
          },
          { selector: 'node[?external]', style: { 'background-color': color('--map-external'), 'border-color': color('--map-edge') } },
          {
            selector: 'edge', style: {
              'curve-style': 'bezier', 'target-arrow-shape': 'triangle', 'line-color': color('--map-edge'),
              'target-arrow-color': color('--map-edge'), width: 1.5, opacity: .8
            }
          },
          { selector: 'edge[?preliminary]', style: { 'line-style': 'dashed', 'line-dash-pattern': [5, 4], 'line-color': color('--map-may'), 'target-arrow-color': color('--map-may'), opacity: .72 } },
          { selector: 'edge[classification="MAY"]', style: { 'line-style': 'dashed', 'line-color': color('--map-may'), 'target-arrow-color': color('--map-may') } },
          { selector: 'edge[classification="UNKNOWN"]', style: { 'line-style': 'dotted', 'line-color': color('--map-unknown'), 'target-arrow-color': color('--map-unknown') } },
          { selector: 'node[activationStatus="UNKNOWN"]', style: { 'border-style': 'dashed', 'border-color': color('--map-unknown') } },
          { selector: '.dimmed', style: { opacity: .13 } },
          { selector: 'node.map-active', style: { 'background-color': color('--map-focus'), 'border-width': 2.5 } },
          { selector: 'edge.map-active', style: { width: 2.3, opacity: 1 } },
          { selector: '.path-highlight', style: { 'border-width': 3, 'border-color': color('--map-unknown'), 'line-color': color('--map-unknown'), 'target-arrow-color': color('--map-unknown'), width: 3 } },
          { selector: 'node[?isSuperNode]', style: { 'background-color': color('--map-focus'), 'background-opacity': .24, 'border-color': '#6366f1', 'border-width': 2.2, shape: 'round-rectangle', height: 48, 'font-size': 12, 'font-weight': 700, 'text-max-width': 220 } },
          { selector: 'node[kind="COMMUNITY"]:parent', style: { 'border-color': '#6366f1', 'border-width': 1.6, 'background-opacity': .18, padding: 24 } },
          { selector: 'node[kind="EXTERNAL_GROUP"]', style: { 'background-color': color('--map-external'), 'background-opacity': .22, 'border-style': 'dashed', 'border-color': color('--map-edge'), 'border-width': 1.5, padding: 20 } },
          { selector: 'node[?misplaced]', style: { 'border-style': 'dashed', 'border-color': '#f59e0b', 'border-width': 2 } },
          { selector: 'node.evo-layer-drift', style: { 'border-style': 'dashed', 'border-color': '#f59e0b', 'border-width': 3, 'background-color': 'rgba(245,158,11,0.22)' } },
          { selector: 'edge[kind="external.dependency"]', style: { 'line-style': 'dotted', 'line-color': color('--map-edge'), 'target-arrow-color': color('--map-edge'), label: 'data(label)', 'font-size': 10, color: color('--map-label') } },
          { selector: 'edge[?isBridge]', style: { 'line-color': '#06b6d4', 'target-arrow-color': '#06b6d4', width: 2, label: 'data(label)', 'font-size': 10, color: color('--map-label') } },
          { selector: 'edge.evo-layer-bridge', style: { 'line-color': '#06b6d4', 'target-arrow-color': '#06b6d4', width: 3, opacity: 1 } },
          { selector: 'node.evo-layer-tangle', style: { 'border-color': '#ef4444', 'border-width': 3 } },
          { selector: 'edge.evo-layer-tangle', style: { 'line-color': '#ef4444', 'target-arrow-color': '#ef4444', width: 2.8, opacity: 1 } }
        ];

        const initialElements = (isCommunityMode && window.EvoCommunityAdvisory?.buildCommunityElements)
          ? window.EvoCommunityAdvisory.buildCommunityElements(graph, collapsedCommunities, degree, label, fullLabel, preliminaryGraph).elements
          : [
              ...graph.nodes.filter(item => included.has(item.id)).map(item => {
                const deg = degree.get(item.id) || 0;
                return {
                  data: {
                    ...item,
                    label: label(item),
                    fullLabel: fullLabel(item),
                    nodeWidth: Math.min(180, isSuperNode(item) ? 148 : 88 + deg * 4),
                    parent: included.has(item.parent) ? item.parent : undefined,
                    activationStatus: item.properties?.activationStatus,
                    external: Boolean(item.origin && item.origin !== 'PROJECT'),
                    misplaced: Boolean(item.properties?.misplaced),
                    isSuperNode: isSuperNode(item),
                    inCycle: Boolean(item.inCycle || item.isCycle)
                  }
                };
              }),
              ...edges.map(item => ({
                data: {
                  ...item,
                  isBridge: Boolean(item.properties?.isBridge),
                  inCycle: Boolean(item.inCycle || item.isCycle),
                  preliminary: Boolean(preliminaryGraph || item.derivation === 'SYNTAX_IMPORT_PREVIEW')
                }
              }))
            ];

        try {
          cy = window.cytoscape({
            container: canvas, elements: initialElements, layout: layoutConfig(layout.value),
            minZoom: .06, maxZoom: 3, wheelSensitivity: .17, style: graphStyle()
          });
        } catch (error) {
          stage.replaceChildren(empty('Could not draw the map', error.message), nodeList(), btn('Retry graph', () => load()));
          rail.replaceChildren(details);
          return;
        }

        if (isCommunityMode && window.EvoCommunityAdvisory?.buildCommunityElements) {
          rebuildCommunityView = () => {
            const built = window.EvoCommunityAdvisory.buildCommunityElements(graph, collapsedCommunities, degree, label, fullLabel, preliminaryGraph);
            cy.batch(() => {
              cy.elements().remove();
              cy.add(built.elements);
            });
            cy.layout(layoutConfig(layout.value)).run();
            applyLayers();
            const extNote = built.hasExternalGroup
              ? (built.externalCollapsed ? ' · external collapsed' : ' · external expanded')
              : '';
            readout.textContent = `${built.collapsedCommunityCount} of ${built.totalCommunityCount} communities collapsed${extNote} · ${built.visibleLeafCount} expanded nodes · click any group box to toggle.`;
          };
        }

        state.cy = cy;
        if (state.pendingGraphViewport && state.pendingGraphViewport.context === selectedContext) {
          try { cy.viewport(state.pendingGraphViewport); } catch (_) { }
          state.pendingGraphViewport = null;
        }
        setRefreshGraphTheme(() => cy?.style(graphStyle()).update());
        canvasObserver = new ResizeObserver(() => { if (active() && cy && !cy.destroyed()) cy.resize(); });
        canvasObserver.observe(canvas);

        let lastCyTapId = null;
        let lastCyTapMs = 0;
        cy.on('tap', 'node', event => {
          const targetId = event.target.id();
          if (isCommunityMode && (targetId.startsWith('compound:community:') || targetId === 'compound:external')) {
            if (collapsedCommunities.has(targetId)) collapsedCommunities.delete(targetId);
            else collapsedCommunities.add(targetId);
            rebuildCommunityView?.();
            return;
          }
          const item = byId.get(targetId);
          const now = Date.now();
          if (item && isSuperNode(item) && lastCyTapId === targetId && now - lastCyTapMs < 380) {
            lastCyTapId = null;
            if (isExpandableSuperNode(item)) void safe(() => expandSuperNode(item));
            else void safe(() => inspectNode(item));
            return;
          }
          lastCyTapId = targetId;
          lastCyTapMs = now;
          selectNode(targetId);
        });
        cy.on('mouseover', 'node', event => {
          const item = byId.get(event.target.id());
          if (!item || compound(item)) return;
          const tier = window.EvoTrust ? window.EvoTrust.inferFromRecord(item) : (item.trustTier || 'SOURCE_SYNTAX');
          hoverBar.replaceChildren(
            el('strong', '', fullLabel(item)),
            window.EvoTrust ? window.EvoTrust.renderBadge(tier, { compact: true }) : null,
            el('span', '', isExpandableSuperNode(item)
              ? 'Double-click to expand community'
              : isSuperNode(item)
                ? 'Collapsed external group · double-click to inspect'
                : `${degree.get(item.id) || 0} edges`)
          );
        });
        cy.on('tap', 'edge', event => safe(() => {
          const item = event.target.data();
          const actions = (item.properties?.evidence ?? []).slice(0, 8).map(reference => ({
            label: 'Open source evidence',
            run: () => inspect('Dependency source', 'SOURCE EVIDENCE', signal => api('/graphs/' + env.graph + '/queries/evidence', { request: graphEnv.request, arguments: { reference } }, signal))
          }));
          return inspect('Dependency evidence', 'DEPENDENCY', item, actions);
        }));
      }

      if (pendingLayer) {
        const toActivate = pendingLayer;
        pendingLayer = null;
        const btnMap = { drift: driftPill, bridges: bridgePill, tangles: tanglePill };
        if (btnMap[toActivate]) {
          layerState[toActivate] = true;
          btnMap[toActivate].classList.add('is-active');
          btnMap[toActivate].setAttribute('aria-pressed', 'true');
          applyLayers();
        }
      }

      expandedDialog = dialog;
      dialog.addEventListener('close', () => {
        if (!active()) return;
        dialog.before(board);
        if (webgl) {
          webgl.resize();
          if (previousViewport) webgl.setViewport(previousViewport);
        } else if (cy) {
          cy.resize();
          if (previousViewport) cy.viewport(previousViewport);
        }
        expand.focus({ preventScroll: true });
      });

      graph.nodes.forEach(item => state.labels.set(item.id, fullLabel(item)));

      selected.addEventListener('change', () => {
        if (selected.value) { selectNode(selected.value, { pulse: true }); return; }
        resetHighlight();
        railTrustHost.replaceChildren();
        spotlight.disabled = inspectButton.disabled = blastButton.disabled = askAiNode.disabled = traceNode.disabled = true;
        readout.textContent = 'Select a node to see where its arrows lead.';
      });

      filter.addEventListener('input', () => {
        resetHighlight();
        const term = filter.value.trim().toLowerCase();
        if (!term) return;
        if (webgl) {
          const matchedIds = new Set();
          const matchedEdges = new Set();
          for (const n of visible) {
            if (fullLabel(n).toLowerCase().includes(term)) matchedIds.add(n.id);
          }
          for (const e of edges) {
            if (matchedIds.has(e.source) || matchedIds.has(e.target)) {
              matchedEdges.add(e.id);
              matchedIds.add(e.source);
              matchedIds.add(e.target);
            }
          }
          webgl.setHighlight({ nodeIds: [...matchedIds], edgeIds: [...matchedEdges], dimOthers: true });
          return;
        }
        if (!cy) return;
        const matches = cy.nodes().not(':parent').filter(item => String(item.data('fullLabel')).toLowerCase().includes(term));
        cy.elements().not(matches.union(matches.neighborhood())).not(':parent').addClass('dimmed');
        matches.addClass('map-active');
      });

      canvas.addEventListener('keydown', event => {
        if (!['+', '=', '-', '0'].includes(event.key)) return;
        event.preventDefault();
        if (event.key === '0') doFit();
        else doZoom(event.key === '-' ? 0.8 : 1.25);
      });

      const destination = selectControl('Path target', dropdownNodes.map(item => [item.id, fullLabel(item)]));
      const pathStatus = el('p', 'graph-message');
      rail.append(append(el('details', 'graph-options'), el('summary', '', 'Paths and export'),
        field('Path target', destination), btn('Find shortest path', async () => {
          if (!selected.value) { pathStatus.textContent = 'Select a start node first.'; return; }
          const srcItem = byId.get(selected.value);
          if (isSuperNode(srcItem) || isSuperNode(byId.get(destination.value))) {
            pathStatus.textContent = 'Expand communities to Micro view to trace shortest paths between concrete symbols.';
            return;
          }
          const pathReq = effectiveQueryRequestFor(srcItem);
          const result = await env.query('shortest-path', { source: selected.value, target: destination.value }, undefined, pathReq);
          if (!active()) return;
          const path = result.items[0];
          if (path) {
            highlightSubset(new Set(path.vertices), path.edges);
          } else {
            resetHighlight();
          }
          pathStatus.textContent = path ? 'Path highlighted where visible on the map.' : 'No path returned for this query.';
          await inspect('Shortest dependency path', 'PATH EVIDENCE', result);
        }), pathStatus, details, btn('Save graph image', async () => {
          const bg = color('--map-paper') || theme.paper;
          if (webgl) {
            webgl.exportPng('dependency-graph.png', bg);
          } else if (cy) {
            const blob = await cy.png({ output: 'blob-promise', full: true, scale: 1, maxWidth: 3000, maxHeight: 3000, bg });
            downloadBlob(blob, 'dependency-graph.png');
          }
        })));

      // Active focus handler for live EvoBus evo:canvas-focus events
      activeFocusHandler = payload => {
        const ids = Array.isArray(payload?.nodeIds) ? payload.nodeIds.filter(Boolean) : [];
        if (!ids.length) return false;
        const present = ids.filter(id => byId.has(id));
        if (present.length) {
          if (webgl) {
            webgl.focusNodes(present, { pulse: true, dimOthers: true, select: true });
            selectNode(present[0], { pulse: true });
          } else if (cy) {
            highlightSubset(new Set(present), null);
            selectNode(present[0], { pulse: true });
          }
          if (payload.openInspector) {
            void safe(() => inspectNode(byId.get(present[0])));
          }
          return true;
        }
        return false;
      };

      const initialFocusIds = Array.isArray(focus.focusNodes) && focus.focusNodes.length
        ? focus.focusNodes
        : (focus.seed ? [focus.seed] : []);
      if (initialFocusIds.length) {
        activeFocusHandler({ nodeIds: initialFocusIds, openInspector: Boolean(focus.openInspector) });
      }
    }

    // Subscribe to EvoBus evo:canvas-focus while this view is mounted
    let unsubFocus = null;
    if (window.EvoBus) {
      unsubFocus = window.EvoBus.on('evo:canvas-focus', payload => {
        if (env.signal.aborted) return;
        const ids = Array.isArray(payload?.nodeIds) ? payload.nodeIds.filter(Boolean) : [];
        if (!ids.length) return;
        const targetCtx = payload.contextId && [...context.options].some(o => o.value === payload.contextId)
          ? payload.contextId
          : null;
        if (targetCtx && context.value !== targetCtx) {
          context.value = targetCtx;
        } else if (activeFocusHandler && activeFocusHandler(payload)) {
          return;
        }
        // Not currently visible on canvas (or context changed) -> reload in MICRO focused on requested nodeIds
        focus = { ...focus, seed: ids[0], focusNodes: ids, openInspector: Boolean(payload.openInspector) };
        legacyControlOverride = false;
        levelMode = 'MICRO';
        syncLevelButtons();
        depthField.hidden = clear.hidden = false;
        void safe(async () => {
          if (targetCtx) await setScope();
          if (!env.signal.aborted) await load(false);
        });
      });
    }

    function resetFocus() { focus = {}; depthField.hidden = clear.hidden = true; }
    function resetRealization() {
      for (const option of projection.options) if (option.value === 'REALIZED') option.disabled = true;
      if (projection.value === 'REALIZED') projection.value = 'BOUNDED_UNION';
    }

    context.addEventListener('change', () => safe(async () => {
      const selectedVal = context.value;
      resetFocus(); resetRealization();
      legacyControlOverride = false;
      expandedCommunities.clear();
      await setScope();
      if (!env.signal.aborted && context.value === selectedVal) await load(true);
    }));
    scope.addEventListener('change', () => safe(() => {
      resetFocus(); resetRealization();
      legacyControlOverride = true;
      return load();
    }));
    groupBy.addEventListener('change', () => safe(() => load()));
    projection.addEventListener('change', () => safe(() => {
      legacyControlOverride = projection.value !== 'BOUNDED_UNION';
      return load();
    }));
    depth.addEventListener('change', () => safe(() => {
      legacyControlOverride = true;
      return load();
    }));
    layout.addEventListener('change', () => state.cy?.layout(layoutConfig(layout.value)).run());

    env.signal.addEventListener('abort', () => {
      unsubFocus?.();
      releaseCanvas();
    }, { once: true });

    await setScope();
    return { node, after: () => safe(() => load(!focus.scope)) };
  }

  const api = Object.freeze({
    renderView,
    loadCytoscape,
    layoutConfig,
    normalizeVisualGraph,
    chooseEngine,
    isSuperNodeItem,
    isExpandableSuperNodeItem,
    resolveEffectiveScopeForNode
  });

  if (typeof window !== 'undefined') {
    window.EvoGraphCanvas = api;
  }
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})();
