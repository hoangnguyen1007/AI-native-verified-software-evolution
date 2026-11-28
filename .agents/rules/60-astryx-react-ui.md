---
trigger: glob
globs: "frontend/**"
description: Astryx v0.7.17 UI workflow and guardrails for React/TSX under frontend/**.
---

# Astryx React UI (`frontend/**`)

Active only for React/TSX under `frontend/**` (current Evo UI in `backend/src/main/resources/web/` is Vanilla JS + Lucian).

- **PS 5.1 CLI:** `npx.cmd --no-install astryx <cmd>`.
- **Entry setup:** import `@astryxdesign/core/reset.css` and `@astryxdesign/core/astryx.css`.
- **Flow:** `build` -> `template` -> `component <Name>` -> `doctor --built <dir>`.
- **Rules:** use Astryx components & CSS tokens; no raw layout `<div>`, hex/px, or `:root` overrides.
