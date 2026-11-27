/* Layer-2 Offline Clean-Room High-DPI WebGL2 + 2D-Canvas Graph Engine (0 CDN, strict CSP, pure DOM).
 * Renders directed graphs at 60fps up to 50,000+ nodes via instanced WebGL2 shaders + 2D HUD label
 * overlay, with automatic seamless HTML5 2D Canvas fallback when WebGL2 is unavailable.
 */
(() => {
  'use strict';

  const COMMUNITY_PALETTE_DARK = [
    '#4fd19a', '#67d8e8', '#a5b4fc', '#fbbf24', '#f472b6', '#38bdf8',
    '#a3e635', '#fb923c', '#c084fc', '#2dd4bf', '#f87171', '#60a5fa'
  ];
  const COMMUNITY_PALETTE_LIGHT = [
    '#146c4e', '#0c6478', '#4338ca', '#b45309', '#be185d', '#0369a1',
    '#4d7c0f', '#c2410c', '#7e22ce', '#0f766e', '#b91c1c', '#1d4ed8'
  ];

  const DEFAULT_THEME_DARK = {
    paper: '#101b21',
    grid: '#263941',
    label: '#e9f1f2',
    labelBg: 'rgba(10, 14, 13, 0.84)',
    node: '#21353d',
    nodeBorder: '#54838a',
    edge: '#87bab2',
    may: '#8db4eb',
    unknown: '#edb777',
    focus: '#4fd19a',
    external: '#263039',
    cycle: '#ef4444',
    violation: '#f87171',
    bridge: '#06b6d4',
    drift: '#f59e0b',
    light: false
  };

  const DEFAULT_THEME_LIGHT = {
    paper: '#f2f6f5',
    grid: '#d1dedb',
    label: '#163035',
    labelBg: 'rgba(255, 255, 255, 0.90)',
    node: '#dcebe8',
    nodeBorder: '#647f7c',
    edge: '#47736b',
    may: '#476ea7',
    unknown: '#91612b',
    focus: '#146c4e',
    external: '#d8e0e8',
    cycle: '#dc2626',
    violation: '#b91c1c',
    bridge: '#0891b2',
    drift: '#d97706',
    light: true
  };

  function hashString(str) {
    let h = 2166136261 >>> 0;
    const s = String(str || '');
    for (let i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i);
      h = Math.imul(h, 16777619) >>> 0;
    }
    return h;
  }

  function parseColorToRgba(input, alphaOverride = null) {
    const s = String(input || '#64748b').trim();
    if (s.startsWith('#')) {
      const hex = s.slice(1);
      if (hex.length === 3) {
        const r = parseInt(hex[0] + hex[0], 16) / 255;
        const g = parseInt(hex[1] + hex[1], 16) / 255;
        const b = parseInt(hex[2] + hex[2], 16) / 255;
        return [r, g, b, alphaOverride ?? 1];
      }
      if (hex.length === 6 || hex.length === 8) {
        const r = parseInt(hex.slice(0, 2), 16) / 255;
        const g = parseInt(hex.slice(2, 4), 16) / 255;
        const b = parseInt(hex.slice(4, 6), 16) / 255;
        const a = hex.length === 8 ? parseInt(hex.slice(6, 8), 16) / 255 : 1;
        return [r, g, b, alphaOverride ?? a];
      }
    }
    const m = s.match(/^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)(?:\s*,\s*([\d.]+))?\s*\)$/i);
    if (m) {
      return [
        Math.min(1, Number(m[1]) / 255),
        Math.min(1, Number(m[2]) / 255),
        Math.min(1, Number(m[3]) / 255),
        alphaOverride ?? (m[4] != null ? Number(m[4]) : 1)
      ];
    }
    return [0.4, 0.55, 0.62, alphaOverride ?? 1];
  }

  function rgbaToCss(rgba) {
    return `rgba(${Math.round(rgba[0] * 255)},${Math.round(rgba[1] * 255)},${Math.round(rgba[2] * 255)},${Number(rgba[3]).toFixed(3)})`;
  }

  /**
   * Pure deterministic layout generator for nodes missing explicit (x, y) coordinates.
   * Clusters by communityId/parent/module on a golden-angle spiral so identical graphs
   * produce identical coordinates without nondeterministic physics jitter.
   */
  function computeDeterministicLayout(rawNodes, rawEdges) {
    const nodes = Array.isArray(rawNodes) ? rawNodes : [];
    const edges = Array.isArray(rawEdges) ? rawEdges : [];
    const deg = new Map();
    for (const e of edges) {
      deg.set(e.source, (deg.get(e.source) || 0) + 1);
      deg.set(e.target, (deg.get(e.target) || 0) + 1);
    }

    const withCoords = nodes.filter(n => {
      const x = n.x ?? n.properties?.x;
      const y = n.y ?? n.properties?.y;
      return Number.isFinite(Number(x)) && Number.isFinite(Number(y));
    });
    const allHaveCoords = nodes.length > 0 && withCoords.length === nodes.length;

    const groups = new Map();
    for (const n of nodes) {
      const gKey = String(n.communityId ?? n.properties?.communityId ?? n.parent ?? n.kind ?? 'default');
      if (!groups.has(gKey)) groups.set(gKey, []);
      groups.get(gKey).push(n);
    }
    const groupKeys = [...groups.keys()].sort((a, b) => a.localeCompare(b));
    const goldenAngle = 2.399963229728653;
    const positions = new Map();

    if (!allHaveCoords) {
      const clusterStep = Math.max(220, Math.sqrt(Math.max(1, nodes.length)) * 34);
      groupKeys.forEach((gKey, gIdx) => {
        const members = groups.get(gKey).slice().sort((a, b) =>
          (deg.get(b.id) || 0) - (deg.get(a.id) || 0) || String(a.id).localeCompare(String(b.id))
        );
        const cgAngle = gIdx * goldenAngle;
        const cgRadius = groupKeys.length === 1 ? 0 : Math.sqrt(gIdx + 0.5) * clusterStep;
        const cx = Math.cos(cgAngle) * cgRadius;
        const cy = Math.sin(cgAngle) * cgRadius;
        const memberSpread = members.length <= 1 ? 0 : Math.max(26, Math.min(54, 180 / Math.sqrt(members.length)));
        members.forEach((m, idx) => {
          const mx = m.x ?? m.properties?.x;
          const my = m.y ?? m.properties?.y;
          if (Number.isFinite(Number(mx)) && Number.isFinite(Number(my))) {
            positions.set(m.id, { x: Number(mx), y: Number(my) });
            return;
          }
          if (idx === 0 && members.length > 1) {
            positions.set(m.id, { x: cx, y: cy });
          } else {
            const angle = idx * goldenAngle;
            const r = Math.sqrt(idx + 0.35) * memberSpread;
            positions.set(m.id, { x: cx + Math.cos(angle) * r, y: cy + Math.sin(angle) * r });
          }
        });
      });
    } else {
      for (const n of nodes) {
        positions.set(n.id, {
          x: Number(n.x ?? n.properties?.x),
          y: Number(n.y ?? n.properties?.y)
        });
      }
    }

    return positions;
  }

  /**
   * Builds a uniform spatial hash grid for O(1) average / O(log N) worst-case hit-testing.
   */
  function buildSpatialGrid(items) {
    if (!items.length) {
      return { cellSize: 64, cells: new Map(), minX: 0, minY: 0, maxX: 0, maxY: 0 };
    }
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const item of items) {
      if (item.x < minX) minX = item.x;
      if (item.y < minY) minY = item.y;
      if (item.x > maxX) maxX = item.x;
      if (item.y > maxY) maxY = item.y;
    }
    const span = Math.max(maxX - minX, maxY - minY, 100);
    const cellSize = Math.max(24, span / Math.max(8, Math.min(256, Math.ceil(Math.sqrt(items.length)))));
    const cells = new Map();
    for (let i = 0; i < items.length; i++) {
      const it = items[i];
      const cx = Math.floor(it.x / cellSize);
      const cy = Math.floor(it.y / cellSize);
      const key = cx + ',' + cy;
      let bucket = cells.get(key);
      if (!bucket) {
        bucket = [];
        cells.set(key, bucket);
      }
      bucket.push(i);
    }
    return { cellSize, cells, minX, minY, maxX, maxY };
  }

  function hitTestGrid(items, grid, wx, wy, scale) {
    if (!items.length || !grid) return null;
    const cs = grid.cellSize;
    const cx = Math.floor(wx / cs);
    const cy = Math.floor(wy / cs);
    const screenPadWorld = 7 / Math.max(scale, 0.01);
    let best = null;
    let bestDistSq = Infinity;
    for (let dx = -1; dx <= 1; dx++) {
      for (let dy = -1; dy <= 1; dy++) {
        const bucket = grid.cells.get((cx + dx) + ',' + (cy + dy));
        if (!bucket) continue;
        for (let k = 0; k < bucket.length; k++) {
          const node = items[bucket[k]];
          const effectiveRadius = Math.max(node.radius, 6 / Math.max(scale, 0.01)) + screenPadWorld;
          const ddx = wx - node.x;
          const ddy = wy - node.y;
          const distSq = ddx * ddx + ddy * ddy;
          if (distSq <= effectiveRadius * effectiveRadius && distSq < bestDistSq) {
            bestDistSq = distSq;
            best = node;
          }
        }
      }
    }
    return best;
  }

  const EDGE_VERT_SRC = `#version 300 es
precision highp float;
in vec2 a_pos;
in vec4 a_color;
uniform vec2 u_resolution;
uniform vec2 u_pan;
uniform float u_scale;
out vec4 v_color;
void main() {
  vec2 screen = a_pos * u_scale + u_pan;
  vec2 clip = (screen / u_resolution) * 2.0 - 1.0;
  gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
  v_color = a_color;
}`;

  const EDGE_FRAG_SRC = `#version 300 es
precision mediump float;
in vec4 v_color;
out vec4 outColor;
void main() {
  outColor = v_color;
}`;

  const NODE_VERT_SRC = `#version 300 es
precision highp float;
in vec2 a_quad;
in vec2 a_center;
in float a_radius;
in vec4 a_fill;
in vec4 a_stroke;
in float a_flags;
uniform vec2 u_resolution;
uniform vec2 u_pan;
uniform float u_scale;
uniform float u_dpr;
out vec2 v_uv;
out vec4 v_fill;
out vec4 v_stroke;
out float v_flags;
out float v_pixelRadius;
void main() {
  float pxRadius = clamp(a_radius * u_scale, 2.5 * u_dpr, 44.0 * u_dpr);
  if (a_flags >= 1.5) {
    pxRadius = clamp(a_radius * u_scale, 7.0 * u_dpr, 56.0 * u_dpr);
  }
  vec2 centerScreen = a_center * u_scale + u_pan;
  vec2 posScreen = centerScreen + a_quad * (pxRadius + 2.5 * u_dpr);
  vec2 clip = (posScreen / u_resolution) * 2.0 - 1.0;
  gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
  v_uv = a_quad * ((pxRadius + 2.5 * u_dpr) / max(pxRadius, 1.0));
  v_fill = a_fill;
  v_stroke = a_stroke;
  v_flags = a_flags;
  v_pixelRadius = pxRadius;
}`;

  const NODE_FRAG_SRC = `#version 300 es
precision mediump float;
in vec2 v_uv;
in vec4 v_fill;
in vec4 v_stroke;
in float v_flags;
in float v_pixelRadius;
out vec4 outColor;
void main() {
  float dist = length(v_uv);
  float aa = 1.6 / max(v_pixelRadius, 2.0);
  if (dist > 1.0 + aa) discard;
  float borderWidth = v_flags >= 2.5 ? 0.24 : (v_flags >= 1.5 ? 0.18 : 0.16);
  float outerAlpha = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, dist);
  float innerEdge = 1.0 - borderWidth;
  float strokeMix = smoothstep(innerEdge - aa, innerEdge + aa, dist);
  vec4 col = mix(v_fill, v_stroke, strokeMix);
  if (v_flags >= 1.5 && v_flags < 2.5) {
    float ringDist = abs(dist - 0.66);
    float innerRing = 1.0 - smoothstep(0.03, 0.03 + aa, ringDist);
    col.rgb = mix(col.rgb, v_stroke.rgb, innerRing * 0.45);
  }
  outColor = vec4(col.rgb, col.a * outerAlpha);
}`;

  function compileShader(gl, type, src) {
    const sh = gl.createShader(type);
    gl.shaderSource(sh, src);
    gl.compileShader(sh);
    if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) {
      gl.deleteShader(sh);
      return null;
    }
    return sh;
  }

  function linkProgram(gl, vertSrc, fragSrc) {
    const vs = compileShader(gl, gl.VERTEX_SHADER, vertSrc);
    const fs = compileShader(gl, gl.FRAGMENT_SHADER, fragSrc);
    if (!vs || !fs) {
      if (vs) gl.deleteShader(vs);
      if (fs) gl.deleteShader(fs);
      return null;
    }
    const prog = gl.createProgram();
    gl.attachShader(prog, vs);
    gl.attachShader(prog, fs);
    gl.linkProgram(prog);
    gl.deleteShader(vs);
    gl.deleteShader(fs);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      gl.deleteProgram(prog);
      return null;
    }
    return prog;
  }

  function create(container, options = {}) {
    if (!container) throw new Error('EvoWebGLGraph.create requires a host container element.');
    const doc = container.ownerDocument || document;
    const win = doc.defaultView || window;
    const prefersReducedMotion = Boolean(win.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches);

    container.style.position = container.style.position || 'relative';
    container.style.overflow = 'hidden';

    const baseCanvas = doc.createElement('canvas');
    baseCanvas.className = 'evo-gl-base';
    baseCanvas.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;display:block;touch-action:none;';

    const hudCanvas = doc.createElement('canvas');
    hudCanvas.className = 'evo-gl-hud';
    hudCanvas.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;display:block;pointer-events:none;';

    container.replaceChildren(baseCanvas, hudCanvas);

    let gl = null;
    let ctx2d = null;
    let rendererKind = 'canvas2d';
    let edgeProg = null, nodeProg = null;
    let edgeVao = null, edgeBuf = null;
    let nodeVao = null, quadBuf = null, instanceBuf = null;

    if (!options.forceCanvas2d) {
      try {
        const candidate = baseCanvas.getContext('webgl2', {
          antialias: true,
          alpha: true,
          premultipliedAlpha: false,
          preserveDrawingBuffer: true
        });
        if (candidate) {
          const ep = linkProgram(candidate, EDGE_VERT_SRC, EDGE_FRAG_SRC);
          const np = linkProgram(candidate, NODE_VERT_SRC, NODE_FRAG_SRC);
          if (ep && np) {
            gl = candidate;
            edgeProg = ep;
            nodeProg = np;
            rendererKind = 'webgl2';
            initGlBuffers();
          } else {
            if (ep) candidate.deleteProgram(ep);
            if (np) candidate.deleteProgram(np);
          }
        }
      } catch (_) {
        gl = null;
      }
    }

    if (!gl) {
      ctx2d = baseCanvas.getContext('2d');
      rendererKind = 'canvas2d';
    }
    const hudCtx = hudCanvas.getContext('2d');

    function initGlBuffers() {
      // Edge VAO: interleaved [x, y, r, g, b, a] = 6 floats
      edgeVao = gl.createVertexArray();
      edgeBuf = gl.createBuffer();
      gl.bindVertexArray(edgeVao);
      gl.bindBuffer(gl.ARRAY_BUFFER, edgeBuf);
      const ePosLoc = gl.getAttribLocation(edgeProg, 'a_pos');
      const eColLoc = gl.getAttribLocation(edgeProg, 'a_color');
      gl.enableVertexAttribArray(ePosLoc);
      gl.vertexAttribPointer(ePosLoc, 2, gl.FLOAT, false, 24, 0);
      gl.enableVertexAttribArray(eColLoc);
      gl.vertexAttribPointer(eColLoc, 4, gl.FLOAT, false, 24, 8);
      gl.bindVertexArray(null);

      // Node VAO: quad [-1..1]^2 + instanced [x, y, radius, fr, fg, fb, fa, sr, sg, sb, sa, flags] = 12 floats
      nodeVao = gl.createVertexArray();
      quadBuf = gl.createBuffer();
      instanceBuf = gl.createBuffer();
      gl.bindVertexArray(nodeVao);
      gl.bindBuffer(gl.ARRAY_BUFFER, quadBuf);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
        -1, -1, 1, -1, -1, 1,
        -1, 1, 1, -1, 1, 1
      ]), gl.STATIC_DRAW);
      const qLoc = gl.getAttribLocation(nodeProg, 'a_quad');
      gl.enableVertexAttribArray(qLoc);
      gl.vertexAttribPointer(qLoc, 2, gl.FLOAT, false, 0, 0);

      gl.bindBuffer(gl.ARRAY_BUFFER, instanceBuf);
      const stride = 48; // 12 floats
      const cLoc = gl.getAttribLocation(nodeProg, 'a_center');
      const rLoc = gl.getAttribLocation(nodeProg, 'a_radius');
      const fLoc = gl.getAttribLocation(nodeProg, 'a_fill');
      const sLoc = gl.getAttribLocation(nodeProg, 'a_stroke');
      const flLoc = gl.getAttribLocation(nodeProg, 'a_flags');

      gl.enableVertexAttribArray(cLoc);
      gl.vertexAttribPointer(cLoc, 2, gl.FLOAT, false, stride, 0);
      gl.vertexAttribDivisor(cLoc, 1);

      gl.enableVertexAttribArray(rLoc);
      gl.vertexAttribPointer(rLoc, 1, gl.FLOAT, false, stride, 8);
      gl.vertexAttribDivisor(rLoc, 1);

      gl.enableVertexAttribArray(fLoc);
      gl.vertexAttribPointer(fLoc, 4, gl.FLOAT, false, stride, 12);
      gl.vertexAttribDivisor(fLoc, 1);

      gl.enableVertexAttribArray(sLoc);
      gl.vertexAttribPointer(sLoc, 4, gl.FLOAT, false, stride, 28);
      gl.vertexAttribDivisor(sLoc, 1);

      gl.enableVertexAttribArray(flLoc);
      gl.vertexAttribPointer(flLoc, 1, gl.FLOAT, false, stride, 44);
      gl.vertexAttribDivisor(flLoc, 1);
      gl.bindVertexArray(null);
    }

    let theme = { ...(options.theme?.light ? DEFAULT_THEME_LIGHT : DEFAULT_THEME_DARK), ...(options.theme || {}) };
    let colorBy = options.colorBy || 'community'; // 'community' | 'trustTier' | 'status'
    let nodes = [];
    let edges = [];
    let nodeById = new Map();
    let grid = null;
    let labelRanked = [];

    let highlightNodes = new Set();
    let highlightEdges = new Set();
    let dimUnmatched = false;
    let selectedId = null;
    let hoveredNode = null;

    let pulseIds = [];
    let pulseStartMs = 0;
    const PULSE_DURATION_MS = 1800;

    let width = Math.max(1, container.clientWidth || 640);
    let height = Math.max(1, container.clientHeight || 420);
    let dpr = Math.min(2, win.devicePixelRatio || 1);

    // Camera in canvas pixel coordinates (scaled by dpr): screen = world * scale + pan
    let camera = { panX: width * dpr * 0.5, panY: height * dpr * 0.5, scale: 1 };
    let anim = null;
    let destroyed = false;
    let needsUpload = true;
    let needsDraw = true;
    let rafId = 0;
    let edgeVertexCount = 0;

    function resolveNodeColors(node) {
      const palette = theme.light ? COMMUNITY_PALETTE_LIGHT : COMMUNITY_PALETTE_DARK;
      const isSelected = node.id === selectedId || highlightNodes.has(node.id);
      const isHovered = hoveredNode && hoveredNode.id === node.id;
      const dimmed = dimUnmatched && !isSelected && !isHovered;
      const alpha = dimmed ? 0.14 : 0.94;
      const strokeAlpha = dimmed ? 0.16 : 1.0;

      let fillHex = theme.node;
      let strokeHex = theme.nodeBorder;

      if (node.isViolation || Number(node.violationCount || 0) > 0) {
        strokeHex = theme.violation;
        fillHex = theme.light ? '#fee2e2' : '#3a1a22';
      } else if (node.isCycle || node.inCycle) {
        strokeHex = theme.cycle;
        fillHex = theme.light ? '#fef2f2' : '#38191f';
      } else if (node.misplaced) {
        strokeHex = theme.drift;
        fillHex = theme.light ? '#fef3c7' : '#3a2d14';
      } else if (colorBy === 'trustTier' && window.EvoTrust) {
        const tm = window.EvoTrust.meta(node.trustTier);
        strokeHex = theme.light ? tm.lightColorHex : tm.colorHex;
        fillHex = node.isSuperNode ? strokeHex : theme.node;
      } else if (node.external) {
        fillHex = theme.external;
        strokeHex = theme.edge;
      } else {
        const cKey = node.communityId ?? node.parent ?? node.kind ?? node.id;
        const idx = hashString(cKey) % palette.length;
        strokeHex = palette[idx];
        fillHex = node.isSuperNode ? strokeHex : theme.node;
      }

      if (isSelected) {
        strokeHex = theme.focus;
        if (!node.isSuperNode) fillHex = theme.focus;
      } else if (isHovered) {
        strokeHex = theme.focus;
      }

      const fill = parseColorToRgba(fillHex, node.isSuperNode ? (dimmed ? 0.16 : 0.34) : alpha);
      const stroke = parseColorToRgba(strokeHex, strokeAlpha);
      const flags = isSelected || isHovered ? 3.0 : (node.isSuperNode ? 2.0 : 1.0);
      return { fill, stroke, flags };
    }

    function resolveEdgeColor(edge) {
      const eHighlighted = highlightEdges.has(edge.id)
        || (selectedId && (edge.source === selectedId || edge.target === selectedId));
      const dimmed = dimUnmatched && !eHighlighted;
      let hex = theme.edge;
      let alpha = dimmed ? 0.05 : (edges.length > 6000 ? 0.28 : edges.length > 1200 ? 0.44 : 0.68);
      if (edge.isViolation) {
        hex = theme.violation;
        alpha = dimmed ? 0.08 : 0.92;
      } else if (edge.isCycle || edge.inCycle) {
        hex = theme.cycle;
        alpha = dimmed ? 0.08 : 0.88;
      } else if (edge.isBridge) {
        hex = theme.bridge;
        alpha = dimmed ? 0.08 : 0.85;
      } else if (edge.classification === 'MAY' || edge.preliminary) {
        hex = theme.may;
      } else if (edge.classification === 'UNKNOWN') {
        hex = theme.unknown;
      }
      if (eHighlighted) {
        alpha = 0.96;
      }
      return parseColorToRgba(hex, alpha);
    }

    function uploadBuffers() {
      needsUpload = false;
      if (!gl) return;

      // Upload edge vertices
      const eData = new Float32Array(edges.length * 12);
      let offset = 0;
      let validEdges = 0;
      for (let i = 0; i < edges.length; i++) {
        const e = edges[i];
        const a = nodeById.get(e.source);
        const b = nodeById.get(e.target);
        if (!a || !b) continue;
        const c = resolveEdgeColor(e);
        eData[offset++] = a.x;
        eData[offset++] = a.y;
        eData[offset++] = c[0];
        eData[offset++] = c[1];
        eData[offset++] = c[2];
        eData[offset++] = c[3];
        eData[offset++] = b.x;
        eData[offset++] = b.y;
        eData[offset++] = c[0];
        eData[offset++] = c[1];
        eData[offset++] = c[2];
        eData[offset++] = c[3];
        validEdges++;
      }
      edgeVertexCount = validEdges * 2;
      gl.bindBuffer(gl.ARRAY_BUFFER, edgeBuf);
      gl.bufferData(gl.ARRAY_BUFFER, eData.subarray(0, offset), gl.DYNAMIC_DRAW);

      // Upload node instances
      const nData = new Float32Array(nodes.length * 12);
      let nOff = 0;
      for (let i = 0; i < nodes.length; i++) {
        const n = nodes[i];
        const { fill, stroke, flags } = resolveNodeColors(n);
        nData[nOff++] = n.x;
        nData[nOff++] = n.y;
        nData[nOff++] = n.radius;
        nData[nOff++] = fill[0];
        nData[nOff++] = fill[1];
        nData[nOff++] = fill[2];
        nData[nOff++] = fill[3];
        nData[nOff++] = stroke[0];
        nData[nOff++] = stroke[1];
        nData[nOff++] = stroke[2];
        nData[nOff++] = stroke[3];
        nData[nOff++] = flags;
      }
      gl.bindBuffer(gl.ARRAY_BUFFER, instanceBuf);
      gl.bufferData(gl.ARRAY_BUFFER, nData, gl.DYNAMIC_DRAW);
    }

    function drawWebGL(cw, ch) {
      if (needsUpload) uploadBuffers();
      gl.viewport(0, 0, cw, ch);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);

      if (edgeVertexCount > 0) {
        gl.useProgram(edgeProg);
        gl.uniform2f(gl.getUniformLocation(edgeProg, 'u_resolution'), cw, ch);
        gl.uniform2f(gl.getUniformLocation(edgeProg, 'u_pan'), camera.panX, camera.panY);
        gl.uniform1f(gl.getUniformLocation(edgeProg, 'u_scale'), camera.scale);
        gl.bindVertexArray(edgeVao);
        gl.drawArrays(gl.LINES, 0, edgeVertexCount);
      }

      if (nodes.length > 0) {
        gl.useProgram(nodeProg);
        gl.uniform2f(gl.getUniformLocation(nodeProg, 'u_resolution'), cw, ch);
        gl.uniform2f(gl.getUniformLocation(nodeProg, 'u_pan'), camera.panX, camera.panY);
        gl.uniform1f(gl.getUniformLocation(nodeProg, 'u_scale'), camera.scale);
        gl.uniform1f(gl.getUniformLocation(nodeProg, 'u_dpr'), dpr);
        gl.bindVertexArray(nodeVao);
        gl.drawArraysInstanced(gl.TRIANGLES, 0, 6, nodes.length);
      }
      gl.bindVertexArray(null);
    }

    function drawCanvas2D(cw, ch) {
      if (!ctx2d) return;
      ctx2d.setTransform(1, 0, 0, 1, 0, 0);
      ctx2d.clearRect(0, 0, cw, ch);
      const margin = 80 * dpr;

      // Draw visible edges
      ctx2d.lineWidth = Math.max(1, 1.3 * dpr);
      for (let i = 0; i < edges.length; i++) {
        const e = edges[i];
        const a = nodeById.get(e.source);
        const b = nodeById.get(e.target);
        if (!a || !b) continue;
        const ax = a.x * camera.scale + camera.panX;
        const ay = a.y * camera.scale + camera.panY;
        const bx = b.x * camera.scale + camera.panX;
        const by = b.y * camera.scale + camera.panY;
        if ((ax < -margin && bx < -margin) || (ax > cw + margin && bx > cw + margin)
            || (ay < -margin && by < -margin) || (ay > ch + margin && by > ch + margin)) {
          continue;
        }
        ctx2d.strokeStyle = rgbaToCss(resolveEdgeColor(e));
        ctx2d.beginPath();
        ctx2d.moveTo(ax, ay);
        ctx2d.lineTo(bx, by);
        ctx2d.stroke();
      }

      // Draw visible nodes
      for (let i = 0; i < nodes.length; i++) {
        const n = nodes[i];
        const sx = n.x * camera.scale + camera.panX;
        const sy = n.y * camera.scale + camera.panY;
        const pxR = n.isSuperNode
          ? Math.max(7 * dpr, Math.min(56 * dpr, n.radius * camera.scale))
          : Math.max(2.5 * dpr, Math.min(44 * dpr, n.radius * camera.scale));
        if (sx + pxR < -margin || sx - pxR > cw + margin || sy + pxR < -margin || sy - pxR > ch + margin) continue;
        const { fill, stroke } = resolveNodeColors(n);
        ctx2d.beginPath();
        ctx2d.arc(sx, sy, pxR, 0, Math.PI * 2);
        ctx2d.fillStyle = rgbaToCss(fill);
        ctx2d.fill();
        ctx2d.lineWidth = Math.max(1.2 * dpr, pxR * 0.16);
        ctx2d.strokeStyle = rgbaToCss(stroke);
        ctx2d.stroke();
        if (n.isSuperNode && pxR > 10 * dpr) {
          ctx2d.beginPath();
          ctx2d.arc(sx, sy, pxR * 0.68, 0, Math.PI * 2);
          ctx2d.lineWidth = 1 * dpr;
          ctx2d.stroke();
        }
      }
    }

    function drawHud(cw, ch, nowMs) {
      if (!hudCtx) return;
      hudCtx.setTransform(1, 0, 0, 1, 0, 0);
      hudCtx.clearRect(0, 0, cw, ch);

      // 1. Arrowheads on zoomed-in or highlighted edges (capped for 60fps)
      const drawArrows = edges.length <= 450 || camera.scale > 0.75 * dpr || selectedId || highlightNodes.size > 0;
      if (drawArrows) {
        let drawnArrows = 0;
        const maxArrows = 360;
        for (let i = 0; i < edges.length && drawnArrows < maxArrows; i++) {
          const e = edges[i];
          const isHot = highlightEdges.has(e.id) || (selectedId && (e.source === selectedId || e.target === selectedId));
          if (dimUnmatched && !isHot) continue;
          const a = nodeById.get(e.source);
          const b = nodeById.get(e.target);
          if (!a || !b || a === b) continue;
          const ax = a.x * camera.scale + camera.panX;
          const ay = a.y * camera.scale + camera.panY;
          const bx = b.x * camera.scale + camera.panX;
          const by = b.y * camera.scale + camera.panY;
          if (bx < 0 || bx > cw || by < 0 || by > ch) continue;
          const dx = bx - ax;
          const dy = by - ay;
          const dist = Math.hypot(dx, dy);
          const targetR = Math.max(4 * dpr, Math.min(44 * dpr, b.radius * camera.scale));
          if (dist < targetR + 14 * dpr) continue;
          const ux = dx / dist;
          const uy = dy / dist;
          const tipX = bx - ux * (targetR + 2 * dpr);
          const tipY = by - uy * (targetR + 2 * dpr);
          const arrowLen = (isHot ? 8 : 6.5) * dpr;
          const arrowW = (isHot ? 4.2 : 3.2) * dpr;
          hudCtx.fillStyle = rgbaToCss(resolveEdgeColor(e));
          hudCtx.beginPath();
          hudCtx.moveTo(tipX, tipY);
          hudCtx.lineTo(tipX - ux * arrowLen - uy * arrowW, tipY - uy * arrowLen + ux * arrowW);
          hudCtx.lineTo(tipX - ux * arrowLen + uy * arrowW, tipY - uy * arrowLen - ux * arrowW);
          hudCtx.closePath();
          hudCtx.fill();
          drawnArrows++;
        }
      }

      // 2. Focus pulse rings (animated over PULSE_DURATION_MS)
      let pulseActive = false;
      if (pulseIds.length && pulseStartMs > 0) {
        const elapsed = nowMs - pulseStartMs;
        if (elapsed < PULSE_DURATION_MS) {
          pulseActive = true;
          const t = elapsed / PULSE_DURATION_MS;
          const wave = (t * 2.5) % 1;
          const alpha = (1 - t) * (1 - wave * 0.55);
          const focusColor = parseColorToRgba(theme.focus, Math.max(0.05, alpha));
          hudCtx.strokeStyle = rgbaToCss(focusColor);
          hudCtx.lineWidth = 2.4 * dpr;
          for (const pid of pulseIds) {
            const n = nodeById.get(pid);
            if (!n) continue;
            const sx = n.x * camera.scale + camera.panX;
            const sy = n.y * camera.scale + camera.panY;
            const baseR = Math.max(6 * dpr, Math.min(48 * dpr, n.radius * camera.scale));
            hudCtx.beginPath();
            hudCtx.arc(sx, sy, baseR + wave * 28 * dpr, 0, Math.PI * 2);
            hudCtx.stroke();
          }
        } else {
          pulseIds = [];
          pulseStartMs = 0;
        }
      }

      // 3. Zoom-aware LOD label culling (0 overlap guaranteed via screen-space box occupancy)
      const occupied = [];
      const padX = 6 * dpr;
      const padY = 4 * dpr;
      const fontSize = Math.round(11.5 * dpr);
      const subFontSize = Math.round(9.5 * dpr);
      hudCtx.font = `600 ${fontSize}px "Source Sans 3", system-ui, sans-serif`;
      hudCtx.textAlign = 'center';
      hudCtx.textBaseline = 'top';

      const maxLabels = Math.min(110, Math.max(18, Math.floor((cw * ch) / (26000 * dpr * dpr))));
      const priorityNodes = [];
      if (hoveredNode) priorityNodes.push(hoveredNode);
      if (selectedId && nodeById.has(selectedId) && nodeById.get(selectedId) !== hoveredNode) {
        priorityNodes.push(nodeById.get(selectedId));
      }
      for (const hid of highlightNodes) {
        const hn = nodeById.get(hid);
        if (hn && hn !== hoveredNode && hn.id !== selectedId) priorityNodes.push(hn);
      }

      const drawnIds = new Set();
      let labelsDrawn = 0;

      const tryLabel = (n, force) => {
        if (!n || drawnIds.has(n.id)) return;
        if (!force && labelsDrawn >= maxLabels) return;
        const sx = n.x * camera.scale + camera.panX;
        const sy = n.y * camera.scale + camera.panY;
        const pxR = n.isSuperNode
          ? Math.max(7 * dpr, Math.min(56 * dpr, n.radius * camera.scale))
          : Math.max(2.5 * dpr, Math.min(44 * dpr, n.radius * camera.scale));
        if (sx < -60 * dpr || sx > cw + 60 * dpr || sy < -30 * dpr || sy > ch + 30 * dpr) return;
        if (!force && !n.isSuperNode && pxR < 3.4 * dpr && nodes.length > 180) return;

        const rawText = String(n.shortLabel || n.label || n.id);
        const text = rawText.length > 28 ? rawText.slice(0, 27) + '…' : rawText;
        const textW = hudCtx.measureText(text).width;
        const hasSub = Boolean(n.isSuperNode && n.memberCount > 0);
        const boxW = textW + padX * 2;
        const boxH = fontSize + (hasSub ? subFontSize + 4 * dpr : 0) + padY * 1.6;
        const bx = sx - boxW / 2;
        const by = sy + pxR + 4 * dpr;

        if (!force) {
          for (let j = 0; j < occupied.length; j++) {
            const o = occupied[j];
            if (bx < o.x2 && bx + boxW > o.x1 && by < o.y2 && by + boxH > o.y1) return;
          }
        }
        occupied.push({ x1: bx - 3 * dpr, y1: by - 2 * dpr, x2: bx + boxW + 3 * dpr, y2: by + boxH + 2 * dpr });
        drawnIds.add(n.id);
        labelsDrawn++;

        const isHot = force || n.id === selectedId || highlightNodes.has(n.id) || (hoveredNode && hoveredNode.id === n.id);
        const alpha = dimUnmatched && !isHot ? 0.28 : 1;
        hudCtx.save();
        hudCtx.globalAlpha = alpha;
        hudCtx.fillStyle = theme.labelBg;
        roundRect(hudCtx, bx, by, boxW, boxH, 4 * dpr);
        hudCtx.fill();
        if (isHot || n.isSuperNode) {
          hudCtx.strokeStyle = isHot ? theme.focus : theme.nodeBorder;
          hudCtx.lineWidth = 1 * dpr;
          hudCtx.stroke();
        }
        hudCtx.fillStyle = theme.label;
        hudCtx.font = `600 ${fontSize}px "Source Sans 3", system-ui, sans-serif`;
        hudCtx.fillText(text, sx, by + padY * 0.75);
        if (hasSub) {
          hudCtx.font = `500 ${subFontSize}px "JetBrains Mono", ui-monospace, monospace`;
          hudCtx.fillStyle = theme.edge;
          const canExpand = n.expandable !== false && !n.externalGroup && String(n.id || '') !== 'community:external';
          hudCtx.fillText(canExpand ? `${n.memberCount} nodes · 2x click` : `${n.memberCount} external`, sx, by + padY * 0.75 + fontSize + 2 * dpr);
        }
        hudCtx.restore();
      };

      for (const pn of priorityNodes) tryLabel(pn, true);
      for (let i = 0; i < labelRanked.length && labelsDrawn < maxLabels; i++) {
        tryLabel(labelRanked[i], false);
      }

      if (pulseActive) requestRender();
    }

    function roundRect(ctx, x, y, w, h, r) {
      const rr = Math.min(r, w / 2, h / 2);
      ctx.beginPath();
      ctx.moveTo(x + rr, y);
      ctx.arcTo(x + w, y, x + w, y + h, rr);
      ctx.arcTo(x + w, y + h, x, y + h, rr);
      ctx.arcTo(x, y + h, x, y, rr);
      ctx.arcTo(x, y, x + w, y, rr);
      ctx.closePath();
    }

    function renderFrame(nowMs) {
      rafId = 0;
      if (destroyed) return;

      if (anim) {
        const t = Math.min(1, (nowMs - anim.startMs) / anim.durationMs);
        const ease = 1 - Math.pow(1 - t, 3);
        camera.panX = anim.from.panX + (anim.to.panX - anim.from.panX) * ease;
        camera.panY = anim.from.panY + (anim.to.panY - anim.from.panY) * ease;
        camera.scale = anim.from.scale + (anim.to.scale - anim.from.scale) * ease;
        if (t >= 1) anim = null;
        else needsDraw = true;
      }

      const cw = Math.max(1, Math.round(width * dpr));
      const ch = Math.max(1, Math.round(height * dpr));
      if (baseCanvas.width !== cw || baseCanvas.height !== ch) {
        baseCanvas.width = cw;
        baseCanvas.height = ch;
        hudCanvas.width = cw;
        hudCanvas.height = ch;
      }

      if (rendererKind === 'webgl2') drawWebGL(cw, ch);
      else drawCanvas2D(cw, ch);
      drawHud(cw, ch, nowMs);

      if (anim || (pulseIds.length && pulseStartMs > 0)) {
        requestRender();
      }
    }

    function requestRender() {
      if (destroyed) return;
      needsDraw = true;
      if (!rafId) rafId = win.requestAnimationFrame(renderFrame);
    }

    function animateCameraTo(target, durationMs = 280) {
      if (prefersReducedMotion || durationMs <= 0) {
        anim = null;
        camera = { ...target };
        requestRender();
        return;
      }
      anim = {
        startMs: win.performance?.now?.() ?? Date.now(),
        durationMs,
        from: { ...camera },
        to: { ...target }
      };
      requestRender();
    }

    function fit(padding = 48, immediate = false) {
      if (!nodes.length) return;
      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
      for (const n of nodes) {
        if (n.x - n.radius < minX) minX = n.x - n.radius;
        if (n.y - n.radius < minY) minY = n.y - n.radius;
        if (n.x + n.radius > maxX) maxX = n.x + n.radius;
        if (n.y + n.radius > maxY) maxY = n.y + n.radius;
      }
      const cw = Math.max(1, width * dpr);
      const ch = Math.max(1, height * dpr);
      const pad = padding * dpr;
      const bw = Math.max(1, maxX - minX);
      const bh = Math.max(1, maxY - minY);
      const scale = Math.min(4 * dpr, Math.max(0.015 * dpr, Math.min((cw - pad * 2) / bw, (ch - pad * 2) / bh)));
      const cx = (minX + maxX) * 0.5;
      const cy = (minY + maxY) * 0.5;
      const target = { panX: cw * 0.5 - cx * scale, panY: ch * 0.5 - cy * scale, scale };
      animateCameraTo(target, immediate ? 0 : 260);
    }

    function zoomBy(factor, anchorCssX = width * 0.5, anchorCssY = height * 0.5, immediate = false) {
      const ax = anchorCssX * dpr;
      const ay = anchorCssY * dpr;
      const nextScale = Math.max(0.015 * dpr, Math.min(12 * dpr, camera.scale * factor));
      const wx = (ax - camera.panX) / camera.scale;
      const wy = (ay - camera.panY) / camera.scale;
      const target = { panX: ax - wx * nextScale, panY: ay - wy * nextScale, scale: nextScale };
      animateCameraTo(target, immediate ? 0 : 160);
    }

    function focusNodes(ids, opts = {}) {
      const list = (Array.isArray(ids) ? ids : [ids]).filter(id => nodeById.has(id));
      if (!list.length) return false;
      const matched = list.map(id => nodeById.get(id));
      if (opts.select !== false) {
        selectedId = matched[0].id;
      }
      if (opts.highlight !== false) {
        highlightNodes = new Set(list);
        highlightEdges = new Set();
        for (const e of edges) {
          if (highlightNodes.has(e.source) || highlightNodes.has(e.target)) {
            if (list.length === 1 || (highlightNodes.has(e.source) && highlightNodes.has(e.target))) {
              highlightEdges.add(e.id);
              highlightNodes.add(e.source);
              highlightNodes.add(e.target);
            }
          }
        }
        dimUnmatched = Boolean(opts.dimOthers ?? true);
        needsUpload = true;
      }
      if (opts.pulse !== false) {
        pulseIds = list.slice();
        pulseStartMs = win.performance?.now?.() ?? Date.now();
      }

      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
      for (const n of matched) {
        const r = Math.max(n.radius * 2.5, 45);
        if (n.x - r < minX) minX = n.x - r;
        if (n.y - r < minY) minY = n.y - r;
        if (n.x + r > maxX) maxX = n.x + r;
        if (n.y + r > maxY) maxY = n.y + r;
      }
      const cw = Math.max(1, width * dpr);
      const ch = Math.max(1, height * dpr);
      const pad = 72 * dpr;
      const bw = Math.max(60, maxX - minX);
      const bh = Math.max(60, maxY - minY);
      const scale = Math.min(2.4 * dpr, Math.max(0.12 * dpr, Math.min((cw - pad * 2) / bw, (ch - pad * 2) / bh)));
      const cx = (minX + maxX) * 0.5;
      const cy = (minY + maxY) * 0.5;
      animateCameraTo({ panX: cw * 0.5 - cx * scale, panY: ch * 0.5 - cy * scale, scale }, 320);
      return true;
    }

    function setData(data, opts = {}) {
      const rawNodes = Array.isArray(data?.nodes) ? data.nodes : [];
      const rawEdges = Array.isArray(data?.edges) ? data.edges : [];
      const deg = new Map();
      for (const e of rawEdges) {
        deg.set(e.source, (deg.get(e.source) || 0) + 1);
        deg.set(e.target, (deg.get(e.target) || 0) + 1);
      }
      const positions = computeDeterministicLayout(rawNodes, rawEdges);

      nodes = rawNodes.map(raw => {
        const pos = positions.get(raw.id) || { x: 0, y: 0 };
        const d = deg.get(raw.id) || 0;
        const isSuperNode = Boolean(
          raw.kind === 'COMMUNITY_SUPER_NODE'
          || raw.isSuperNode
          || raw.properties?.expandable
          || String(raw.id || '').startsWith('community:')
        );
        const memberCount = Number(raw.memberCount ?? raw.properties?.memberCount ?? 0);
        const radius = isSuperNode
          ? Math.max(16, Math.min(42, 14 + Math.sqrt(Math.max(1, memberCount)) * 2.8))
          : Math.max(5.5, Math.min(18, 5.5 + Math.log2(d + 1) * 2.3));
        const trustTier = window.EvoTrust
          ? window.EvoTrust.inferFromRecord(raw)
          : (raw.trustTier || raw.properties?.trustTier || 'SOURCE_SYNTAX');
        return {
          ...raw,
          raw,
          x: pos.x,
          y: pos.y,
          radius,
          degree: d,
          isSuperNode,
          memberCount,
          trustTier,
          communityId: raw.communityId ?? raw.properties?.communityId ?? raw.parent ?? null,
          external: Boolean(raw.external || (raw.origin && raw.origin !== 'PROJECT')),
          misplaced: Boolean(raw.misplaced || raw.properties?.misplaced),
          isCycle: Boolean(raw.isCycle || raw.inCycle || raw.isCycleParticipant),
          isViolation: Boolean(raw.isViolation || Number(raw.violationCount || 0) > 0),
          shortLabel: raw.shortLabel || raw.label || raw.id
        };
      });

      nodeById = new Map(nodes.map(n => [n.id, n]));
      edges = rawEdges.map((e, i) => ({
        ...e,
        id: e.id || `e:${e.source}->${e.target}:${i}`,
        isBridge: Boolean(e.isBridge || e.properties?.isBridge),
        isCycle: Boolean(e.isCycle || e.inCycle),
        isViolation: Boolean(e.isViolation)
      }));

      grid = buildSpatialGrid(nodes);
      labelRanked = nodes.slice().sort((a, b) =>
        (b.isSuperNode ? 1 : 0) - (a.isSuperNode ? 1 : 0)
        || (b.memberCount || 0) - (a.memberCount || 0)
        || (b.degree || 0) - (a.degree || 0)
        || String(a.id).localeCompare(String(b.id))
      );

      selectedId = null;
      hoveredNode = null;
      highlightNodes.clear();
      highlightEdges.clear();
      dimUnmatched = false;
      needsUpload = true;
      if (opts.fit !== false) fit(48, true);
      else requestRender();
    }

    function setTheme(nextTheme) {
      const base = nextTheme?.light ? DEFAULT_THEME_LIGHT : DEFAULT_THEME_DARK;
      theme = { ...base, ...(nextTheme || {}) };
      needsUpload = true;
      requestRender();
    }

    function setColorBy(mode) {
      if (['community', 'trustTier', 'status'].includes(mode)) {
        colorBy = mode;
        needsUpload = true;
        requestRender();
      }
    }

    function setHighlight({ nodeIds = [], edgeIds = [], dimOthers = true, pulse = false, selectFirst = false } = {}) {
      highlightNodes = new Set(nodeIds);
      highlightEdges = new Set(edgeIds);
      dimUnmatched = Boolean(dimOthers && (highlightNodes.size > 0 || highlightEdges.size > 0));
      if (selectFirst && nodeIds.length && nodeById.has(nodeIds[0])) {
        selectedId = nodeIds[0];
      }
      if (pulse && nodeIds.length) {
        pulseIds = nodeIds.slice();
        pulseStartMs = win.performance?.now?.() ?? Date.now();
      }
      needsUpload = true;
      requestRender();
    }

    function clearHighlight() {
      selectedId = null;
      highlightNodes.clear();
      highlightEdges.clear();
      dimUnmatched = false;
      pulseIds = [];
      pulseStartMs = 0;
      needsUpload = true;
      requestRender();
    }

    function resize() {
      const rect = container.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        width = rect.width;
        height = rect.height;
      }
      dpr = Math.min(2, win.devicePixelRatio || 1);
      requestRender();
    }

    function toDataURL(bgColor) {
      const cw = Math.max(1, baseCanvas.width || Math.round(width * dpr));
      const ch = Math.max(1, baseCanvas.height || Math.round(height * dpr));
      // Ensure latest frame is drawn synchronously before compositing
      if (rendererKind === 'webgl2') drawWebGL(cw, ch);
      else drawCanvas2D(cw, ch);
      drawHud(cw, ch, win.performance?.now?.() ?? Date.now());

      const out = doc.createElement('canvas');
      out.width = cw;
      out.height = ch;
      const octx = out.getContext('2d');
      octx.fillStyle = bgColor || theme.paper;
      octx.fillRect(0, 0, cw, ch);
      octx.drawImage(baseCanvas, 0, 0);
      octx.drawImage(hudCanvas, 0, 0);
      return out.toDataURL('image/png');
    }

    function exportPng(filename = 'evo-dependency-map.png', bgColor) {
      const url = toDataURL(bgColor);
      const a = doc.createElement('a');
      a.href = url;
      a.download = filename;
      doc.body.append(a);
      a.click();
      a.remove();
      return url;
    }

    // Pointer interaction state
    let dragging = false;
    let dragged = false;
    let dragStartX = 0, dragStartY = 0;
    let dragPanX = 0, dragPanY = 0;
    let lastTapNodeId = null;
    let lastTapMs = 0;
    let lastDblClickNodeId = null;
    let lastDblClickMs = 0;

    function fireDoubleClickOnce(hit) {
      const now = Date.now();
      if (lastDblClickNodeId === hit.id && now - lastDblClickMs < 250) return;
      lastDblClickNodeId = hit.id;
      lastDblClickMs = now;
      options.onNodeDoubleClick?.(hit.raw, hit);
    }

    function clientToWorld(clientX, clientY) {
      const rect = baseCanvas.getBoundingClientRect();
      const sx = (clientX - rect.left) * dpr;
      const sy = (clientY - rect.top) * dpr;
      return {
        wx: (sx - camera.panX) / camera.scale,
        wy: (sy - camera.panY) / camera.scale,
        cssX: clientX - rect.left,
        cssY: clientY - rect.top
      };
    }

    const onPointerDown = ev => {
      if (ev.button !== 0) return;
      anim = null;
      dragging = true;
      dragged = false;
      dragStartX = ev.clientX;
      dragStartY = ev.clientY;
      dragPanX = camera.panX;
      dragPanY = camera.panY;
      try { baseCanvas.setPointerCapture(ev.pointerId); } catch (_) { }
    };

    const onPointerMove = ev => {
      if (dragging) {
        const dx = ev.clientX - dragStartX;
        const dy = ev.clientY - dragStartY;
        if (!dragged && Math.hypot(dx, dy) > 4) dragged = true;
        if (dragged) {
          camera.panX = dragPanX + dx * dpr;
          camera.panY = dragPanY + dy * dpr;
          baseCanvas.style.cursor = 'grabbing';
          requestRender();
          return;
        }
      }
      const { wx, wy } = clientToWorld(ev.clientX, ev.clientY);
      const hit = hitTestGrid(nodes, grid, wx, wy, camera.scale);
      if (hit !== hoveredNode) {
        hoveredNode = hit;
        baseCanvas.style.cursor = hit ? 'pointer' : 'grab';
        needsUpload = true;
        requestRender();
        options.onNodeHover?.(hit ? hit.raw : null, hit);
      }
    };

    const onPointerUp = ev => {
      if (!dragging) return;
      dragging = false;
      baseCanvas.style.cursor = hoveredNode ? 'pointer' : 'grab';
      try { baseCanvas.releasePointerCapture(ev.pointerId); } catch (_) { }
      if (dragged) return;

      const { wx, wy } = clientToWorld(ev.clientX, ev.clientY);
      const hit = hitTestGrid(nodes, grid, wx, wy, camera.scale);
      if (hit) {
        if (ev.pointerType && ev.pointerType !== 'mouse') {
          const now = Date.now();
          if (lastTapNodeId === hit.id && now - lastTapMs < 360) {
            lastTapNodeId = null;
            lastTapMs = 0;
            fireDoubleClickOnce(hit);
            return;
          }
          lastTapNodeId = hit.id;
          lastTapMs = now;
        } else {
          lastTapNodeId = null;
          lastTapMs = 0;
        }
        selectedId = hit.id;
        needsUpload = true;
        requestRender();
        options.onNodeClick?.(hit.raw, hit);
      } else {
        lastTapNodeId = null;
        options.onBackgroundClick?.();
      }
    };

    const onWheel = ev => {
      ev.preventDefault();
      const rect = baseCanvas.getBoundingClientRect();
      const factor = ev.deltaY < 0 ? 1.18 : 0.85;
      zoomBy(factor, ev.clientX - rect.left, ev.clientY - rect.top, true);
    };

    const onDblClick = ev => {
      const { wx, wy } = clientToWorld(ev.clientX, ev.clientY);
      const hit = hitTestGrid(nodes, grid, wx, wy, camera.scale);
      if (hit) {
        ev.preventDefault();
        fireDoubleClickOnce(hit);
      }
    };

    baseCanvas.style.cursor = 'grab';
    baseCanvas.addEventListener('pointerdown', onPointerDown);
    baseCanvas.addEventListener('pointermove', onPointerMove);
    baseCanvas.addEventListener('pointerup', onPointerUp);
    baseCanvas.addEventListener('pointercancel', onPointerUp);
    baseCanvas.addEventListener('wheel', onWheel, { passive: false });
    baseCanvas.addEventListener('dblclick', onDblClick);

    const resizeObs = typeof ResizeObserver === 'function' ? new ResizeObserver(resize) : null;
    resizeObs?.observe(container);
    resize();

    function destroy() {
      if (destroyed) return;
      destroyed = true;
      if (rafId) win.cancelAnimationFrame(rafId);
      resizeObs?.disconnect();
      baseCanvas.removeEventListener('pointerdown', onPointerDown);
      baseCanvas.removeEventListener('pointermove', onPointerMove);
      baseCanvas.removeEventListener('pointerup', onPointerUp);
      baseCanvas.removeEventListener('pointercancel', onPointerUp);
      baseCanvas.removeEventListener('wheel', onWheel);
      baseCanvas.removeEventListener('dblclick', onDblClick);
      if (gl) {
        if (edgeVao) gl.deleteVertexArray(edgeVao);
        if (nodeVao) gl.deleteVertexArray(nodeVao);
        if (edgeBuf) gl.deleteBuffer(edgeBuf);
        if (quadBuf) gl.deleteBuffer(quadBuf);
        if (instanceBuf) gl.deleteBuffer(instanceBuf);
        if (edgeProg) gl.deleteProgram(edgeProg);
        if (nodeProg) gl.deleteProgram(nodeProg);
      }
      baseCanvas.remove();
      hudCanvas.remove();
      nodes = [];
      edges = [];
      nodeById.clear();
    }

    return Object.freeze({
      setData,
      setTheme,
      setColorBy,
      setHighlight,
      clearHighlight,
      focusNodes,
      fit: pad => fit(pad ?? 48, false),
      zoomIn: () => zoomBy(1.25),
      zoomOut: () => zoomBy(0.8),
      getViewport: () => ({ panX: camera.panX, panY: camera.panY, scale: camera.scale }),
      setViewport: vp => {
        if (!vp) return;
        anim = null;
        camera = {
          panX: Number.isFinite(vp.panX) ? vp.panX : camera.panX,
          panY: Number.isFinite(vp.panY) ? vp.panY : camera.panY,
          scale: Number.isFinite(vp.scale) ? vp.scale : camera.scale
        };
        requestRender();
      },
      resize,
      toDataURL,
      exportPng,
      getRendererKind: () => rendererKind,
      getNodeCount: () => nodes.length,
      getEdgeCount: () => edges.length,
      getStats: () => ({ rendererKind, nodes: nodes.length, edges: edges.length }),
      destroy
    });
  }

  const api = Object.freeze({
    create,
    computeDeterministicLayout,
    buildSpatialGrid,
    hitTestGrid
  });

  if (typeof window !== 'undefined') {
    window.EvoWebGLGraph = api;
  }
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }
})();
