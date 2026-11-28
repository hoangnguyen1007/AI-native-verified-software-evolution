# FSE 2027 Tool Demo — 4-Minute Video & Live Walkthrough Script

**Target Duration:** `04:00` | **Container:** `evo-workbench-online:latest` (`http://127.0.0.1:8080`)  
**Zero-Overlap Check:** Showcases Workbench UX, epistemic profiles, MCP onboarding, and isolated verification only (no Paper #1–#3 ablation tables or formal proofs).

---

## Timeline & Narration Cue Sheet

| Time | Scene / Action | Key Visual & Claim Shown |
|---|---|---|
| `0:00 – 0:45` | **Turnkey Launch & Host Safety (`I1`)** | Run `docker compose up -d`. Open `http://127.0.0.1:8080`. Show `GET /api/v1/health` (`onlineMode: true`) and confirm local filesystem checkouts return `403 LOCAL_CHECKOUT_DISABLED_IN_ONLINE_MODE` (`0%` host build execution). |
| `0:45 – 1:45` | **Epistemic Switcher (`strict` vs `dfd-export`) & Health v2** | Connect an HTTPS repository, run passive scan, and toggle `strict` (direct canonical edges) vs `dfd-export` (derived flows). Point out Health Score v2 (`Confidence` and `Coverage` displayed separately from score; explicit `CapabilityGapRecord` denominator). |
| `1:45 – 2:30` | **Spring Conditional Truth (`MUST`/`MAY`/`NEVER`/`UNKNOWN`)** | Open Spring Context view: show a `MAY` conditional bean alongside its active/inactive configuration witnesses and explain why Evo avoids flattening conditional truth into a single world. |
| `2:30 – 3:20` | **SKG-RTS Repair Studio & Multi-JDK Tier-1/Tier-2 Isolation** | Select a finding, show the `22` deterministic AST/OpenRewrite recipes, inspect the unified diff, and show isolated verification results (`--network=none --read-only --cap-drop=ALL`, JDK `8/11/17/21`, 3-Layer Certification: `Compile&Test + JaCoCo` ∧ `AST Purity` ∧ `Negative Control`). |
| `3:20 – 4:00` | **Streamable HTTP MCP (`2025-11-25`) & Live Verifier** | Click **MCP · AI Agent** in header, copy `.mcp.json`, and run `python docs/fse-2027-tool-demo/verify-demo.py` showing `23` tools, `7` `evo://` resources, and `403 FORBIDDEN_ORIGIN` security enforcement. |

---

## Pre-Recording Verification Checklist

- [ ] `docker image inspect evo-workbench-online:latest` succeeds and matches rebuilt `backend/target/evo-workbench.jar`.
- [ ] `python docs/fse-2027-tool-demo/verify-demo.py` exits `0` with `"status": "PASS"`.
- [ ] No real API keys or local private paths appear in browser tabs or terminal recordings.
