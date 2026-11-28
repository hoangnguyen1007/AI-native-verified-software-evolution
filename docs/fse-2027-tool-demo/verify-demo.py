#!/usr/bin/env python3
"""Turnkey live verification script for the FSE 2027 Evo Workbench Tool Demo container."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request


def http_get_json(url: str, timeout: float = 5.0) -> tuple[int, dict]:
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))


def mcp_post(
    mcp_url: str,
    payload: dict,
    origin: str,
    session_id: str | None = None,
) -> tuple[int, dict, str | None]:
    req = urllib.request.Request(
        mcp_url,
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
    )
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    req.add_header("Origin", origin)
    if session_id:
        req.add_header("Mcp-Session-Id", session_id)
    try:
        with urllib.request.urlopen(req, timeout=10.0) as resp:
            raw = resp.read().decode("utf-8")
            new_sid = resp.headers.get("Mcp-Session-Id") or session_id
            if raw.lstrip().startswith("data:"):
                lines = [line[5:].strip() for line in raw.splitlines() if line.startswith("data:")]
                return resp.status, json.loads(lines[-1]), new_sid
            return resp.status, json.loads(raw), new_sid
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(body)
        except Exception:
            parsed = {"raw": body}
        return exc.code, parsed, session_id


def verify_live_workbench(base_url: str) -> dict:
    base = base_url.rstrip("/")
    health_code, health_doc = http_get_json(f"{base}/api/v1/health")
    health_data = health_doc.get("data", health_doc)
    assert health_code == 200 and health_data.get("status") == "ok", f"Unexpected health: {health_doc}"
    assert health_data.get("onlineMode") is True, f"Expected onlineMode=true: {health_data}"
    assert health_data.get("mcpProtocolVersion") == "2025-11-25", f"Unexpected MCP protocol: {health_data}"

    cap_code, cap_doc = http_get_json(f"{base}/api/v1")
    cap_data = cap_doc.get("data", cap_doc)
    assert cap_code == 200, f"Unexpected /api/v1 status: {cap_code}"
    assert cap_data.get("repairRecipeCount") == 22, f"Expected 22 repair recipes: {cap_data.get('repairRecipeCount')}"

    mcp_url = f"{base}/mcp"
    bad_code, bad_doc, _ = mcp_post(
        mcp_url,
        {"jsonrpc": "2.0", "id": 0, "method": "initialize", "params": {"protocolVersion": "2025-11-25"}},
        origin="https://evil.example",
    )
    assert bad_code == 403, f"Expected 403 FORBIDDEN_ORIGIN, got {bad_code}: {bad_doc}"
    bad_err = bad_doc.get("error", {})
    assert bad_err.get("code") == "FORBIDDEN_ORIGIN", f"Expected FORBIDDEN_ORIGIN error code: {bad_doc}"

    init_code, init_doc, sid = mcp_post(
        mcp_url,
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-11-25",
                "capabilities": {},
                "clientInfo": {"name": "fse2027-demo-verifier", "version": "1.0"},
            },
        },
        origin=base,
    )
    assert init_code == 200 and sid, f"MCP initialize failed: code={init_code}, sid={sid}, body={init_doc}"
    assert init_doc["result"]["protocolVersion"] == "2025-11-25"

    _, tools_doc, _ = mcp_post(mcp_url, {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}, origin=base, session_id=sid)
    tools = tools_doc["result"]["tools"]
    assert len(tools) == 23, f"Expected 23 MCP tools, got {len(tools)}"

    _, res_doc, _ = mcp_post(mcp_url, {"jsonrpc": "2.0", "id": 3, "method": "resources/list", "params": {}}, origin=base, session_id=sid)
    resources = res_doc["result"]["resources"]
    assert len(resources) == 7, f"Expected 7 MCP resources, got {len(resources)}"

    _, schema_doc, _ = mcp_post(
        mcp_url,
        {"jsonrpc": "2.0", "id": 4, "method": "resources/read", "params": {"uri": "evo://schema"}},
        origin=base,
        session_id=sid,
    )
    contents = schema_doc.get("result", {}).get("contents", [])
    assert contents and contents[0].get("text"), f"Empty evo://schema resource response: {schema_doc}"

    return {
        "status": "PASS",
        "health": health_data.get("status"),
        "onlineMode": health_data.get("onlineMode"),
        "mcpProtocolVersion": init_doc["result"]["protocolVersion"],
        "repairRecipeCount": cap_data.get("repairRecipeCount"),
        "forbiddenOriginHttpStatus": bad_code,
        "mcpToolCount": len(tools),
        "mcpResourceCount": len(resources),
        "schemaResourceBytes": len(contents[0]["text"].encode("utf-8")),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify FSE 2027 Evo Workbench Docker demo.")
    parser.add_argument("--image", default="evo-workbench-online:latest", help="Docker image tag to boot")
    parser.add_argument("--port", type=int, default=18080, help="Host loopback port to bind")
    parser.add_argument("--base-url", default="", help="Optional running server URL (skips container launch)")
    args = parser.parse_args()

    if args.base_url:
        summary = verify_live_workbench(args.base_url)
        print(json.dumps(summary, indent=2))
        return 0

    container_name = "evo-fse2027-demo-verify"
    subprocess.run(["docker", "rm", "-f", container_name], capture_output=True)
    subprocess.check_output(
        [
            "docker", "run", "-d",
            "--name", container_name,
            "-p", f"127.0.0.1:{args.port}:8080",
            "-e", "EVO_ONLINE_MODE=true",
            args.image,
        ],
        text=True,
    )
    try:
        base_url = f"http://127.0.0.1:{args.port}"
        for _ in range(45):
            try:
                code, doc = http_get_json(f"{base_url}/api/v1/health", timeout=2.0)
                if code == 200 and doc.get("data", doc).get("status") == "ok":
                    break
            except Exception:
                time.sleep(1.0)
        else:
            logs = subprocess.check_output(["docker", "logs", container_name], text=True)
            raise RuntimeError(f"Demo container health check timed out:\n{logs[-2000:]}")
        summary = verify_live_workbench(base_url)
        summary["imageId"] = subprocess.check_output(
            ["docker", "image", "inspect", "--format", "{{.Id}}", args.image], text=True
        ).strip()
        print(json.dumps(summary, indent=2))
        return 0
    finally:
        subprocess.run(["docker", "rm", "-f", container_name], capture_output=True)


if __name__ == "__main__":
    sys.exit(main())
