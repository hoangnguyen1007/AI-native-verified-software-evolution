# Evo Workbench — FSE 2027 Tool Demonstrations Artifact

**Target Venue:** FSE 2027 Tool Demonstrations (`26/01/2027`, Zero-Overlap Boundary)  
**Primary Product Surface:** Online Web App Workbench (`evo-workbench-online:latest`, `backend/target/evo-workbench.jar`, Vanilla HTML/CSS/JS + Cytoscape + Lucian UI, Streamable HTTP MCP `2025-11-25`).

> **Zero-Overlap Boundary:** This artifact demonstrates the turnkey Online Workbench UX, epistemic profile switching, Streamable HTTP MCP onboarding, and host-safe isolated repair verification (`I1–I6`). Core algorithmic ablations and multi-paper scientific evaluations remain reserved for Research Papers #1–#3.

---

## 1. Turnkey Launch (1 Command)

### Option A — Docker Compose (Recommended)

```bash
docker compose up -d
```

Open **`http://127.0.0.1:8080`** in any modern browser.

### Option B — Standalone Docker Run

```bash
docker run -d --name evo-workbench-online -p 127.0.0.1:8080:8080 -e EVO_ONLINE_MODE=true evo-workbench-online:latest
```

### Option C — Standalone Shaded JAR

```bash
java -Xms512m -Xmx4g -jar backend/target/evo-workbench.jar --online
```

---

## 2. Live 4-Minute Walkthrough

1. **Host-Safe Passive Acquisition (`--build=off` / `EVO_ONLINE_MODE=true`):** Connect a Git repository over HTTPS (local host checkouts are rejected with `403 LOCAL_CHECKOUT_DISABLED_IN_ONLINE_MODE`). Target builds never run on the host.
2. **Epistemic Profile Switcher (`STRICT` vs `DFD_EXPORT`):** Toggle per-job projection profiles (`strict` direct canonical edges vs `dfd-export` derived dataflow views) while preserving SHA-256 snapshot bit-identity and closed-denominator capability gaps.
3. **Architecture Health v2 & Conditional Spring Reasoning:** Inspect 7-dimension Health Score v2 (`Confidence` kept separate from `Coverage`), Leiden communities, Tarjan SCC cycles, and Spring `MUST`/`MAY`/`NEVER`/`UNKNOWN` configuration witnesses.
4. **SKG-RTS Repair Studio (`22` Built-In Recipes + Isolated Multi-JDK Sandbox):** Inspect applicable deterministic AST/OpenRewrite recipes (`16` DI + `6` OO/compound recipes), preview unified diffs, and verify candidates inside `--network=none --read-only --cap-drop=ALL` multi-JDK (`8/11/17/21`) containers with 3-Layer Certification (`Compile&Test + JaCoCo` ∧ `AST Purity` ∧ `Negative Control`).
5. **Streamable HTTP MCP (`POST /mcp`, `2025-11-25`):** Connect IDE agents (Claude Code, Cursor, Antigravity) to **`23` read-only tools** and **`7` `evo://` resources** with DNS-rebinding `Origin` enforcement (`403 FORBIDDEN_ORIGIN`) and secret redaction.

---

## 3. One-Command Automated Verification

With `evo-workbench-online:latest` built locally, run:

```bash
python docs/fse-2027-tool-demo/verify-demo.py
```

The script boots an ephemeral container (`evo-fse2027-demo-verify`) on `127.0.0.1:18080`, verifies `/api/v1/health`, `/api/v1` (`repairRecipeCount == 22`), `403 FORBIDDEN_ORIGIN` on untrusted `Origin`, and `POST /mcp` (`initialize` `2025-11-25`, `23` tools, `7` resources, `resources/read evo://schema`), then removes the container cleanly.

---

## 4. Related Documentation

- [MCP Ecosystem Quickstart](../mcp-quickstart.md)
- [CI/CD Integration Guide](../ci-integration.md)
- [Captured-Repository Repair Contract](../architecture/captured-repository-repair.md)
- [Current State](../current-state.md)
