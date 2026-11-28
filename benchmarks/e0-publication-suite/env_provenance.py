#!/usr/bin/env python3
"""Execution-environment provenance collector for Evo publication benchmarks.

Guarantees truthful hardware/OS/runtime reporting across native Ubuntu LTS,
generic native Linux, WSL2 Linux, Windows host + Docker Desktop (WSL2), and macOS
so WSL2 Docker Desktop measurements are NEVER mislabeled as native Ubuntu LTS.
"""

from __future__ import annotations

import datetime as dt
import json
import os
import platform
import re
import subprocess
from pathlib import Path
from typing import Any


def _run_cmd(cmd: list[str], timeout_s: int = 15) -> str:
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout_s,
            check=False,
        )
        out = (proc.stdout or "").strip()
        err = (proc.stderr or "").strip()
        return out if out else err
    except (OSError, subprocess.SubprocessError):
        return ""


def _read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace").strip()
    except OSError:
        return ""


def parse_os_release(text: str) -> dict[str, str]:
    parsed: dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        parsed[k.strip()] = v.strip().strip('"').strip("'")
    return parsed


def detect_wsl2_from_strings(*texts: str) -> bool:
    combined = " ".join(t for t in texts if t).lower()
    return ("microsoft" in combined) or ("wsl2" in combined) or ("wsl" in combined)


def is_ubuntu_lts(os_release: dict[str, str], pretty_name: str = "") -> bool:
    os_id = os_release.get("ID", "").lower()
    version = os_release.get("VERSION", "")
    pretty = os_release.get("PRETTY_NAME", pretty_name or "")
    combined = f"{version} {pretty}".lower()
    return (os_id == "ubuntu" or "ubuntu" in combined) and ("lts" in combined)


def classify_environment(
    *,
    host_system: str,
    host_proc_version: str = "",
    host_os_release: dict[str, str] | None = None,
    execution_target: str = "host",
    docker_os: str = "",
    docker_kernel: str = "",
    container_uname: str = "",
    container_proc_version: str = "",
    container_os_release: dict[str, str] | None = None,
) -> dict[str, Any]:
    """Deterministically classify execution environment & compute honest publication caveat."""
    sys_low = (host_system or "").strip().lower()
    host_rel = host_os_release or {}
    cont_rel = container_os_release or {}
    target_low = (execution_target or "host").strip().lower()

    host_is_wsl2 = (sys_low == "linux") and detect_wsl2_from_strings(host_proc_version)
    docker_is_wsl2 = detect_wsl2_from_strings(
        docker_os, docker_kernel, container_uname, container_proc_version
    ) or ("docker desktop" in (docker_os or "").lower())

    is_wsl2 = host_is_wsl2 or (target_low == "docker" and docker_is_wsl2)

    if target_low == "docker":
        if sys_low == "windows":
            env_class = "windows-host-docker-wsl2" if docker_is_wsl2 else "windows-host-docker"
        elif sys_low == "darwin":
            env_class = "macos-host-docker"
        elif sys_low == "linux":
            if is_wsl2:
                env_class = "wsl2-linux-docker"
            elif is_ubuntu_lts(host_rel):
                env_class = "native-linux-ubuntu-lts-docker"
            else:
                env_class = "native-linux-docker"
        else:
            env_class = f"{sys_low or 'unknown'}-docker"
    else:
        if sys_low == "linux":
            if host_is_wsl2:
                env_class = "wsl2-linux"
            elif is_ubuntu_lts(host_rel):
                env_class = "native-linux-ubuntu-lts"
            else:
                env_class = "native-linux"
        elif sys_low == "windows":
            env_class = "windows-host"
        elif sys_low == "darwin":
            env_class = "macos-host"
        else:
            env_class = sys_low or "unknown"

    native_ubuntu_lts = env_class in ("native-linux-ubuntu-lts", "native-linux-ubuntu-lts-docker")
    if native_ubuntu_lts:
        caveat = ""
    else:
        detail_bits = [env_class]
        if is_wsl2:
            detail_bits.append("WSL2 virtualized kernel detected")
        if docker_os:
            detail_bits.append(f"docker_os={docker_os}")
        if docker_kernel:
            detail_bits.append(f"kernel={docker_kernel}")
        elif container_uname:
            detail_bits.append(f"container_uname={container_uname}")
        caveat = (
            "NON-NATIVE-UBUNTU-LTS: Executed on "
            + " (".join([detail_bits[0], "; ".join(detail_bits[1:])]) + (")" if len(detail_bits) > 1 else "")
            + ". Do NOT label these timings as native Ubuntu LTS bare-metal/VM results; "
            "final camera-ready wall-clock tables require re-running this harness on native Ubuntu LTS hardware."
        )

    container_pretty = cont_rel.get("PRETTY_NAME", "")
    return {
        "environment_class": env_class,
        "execution_target": target_low,
        "is_wsl2": is_wsl2,
        "is_native_ubuntu_lts": native_ubuntu_lts,
        "host_is_ubuntu_lts": (sys_low == "linux" and not host_is_wsl2 and is_ubuntu_lts(host_rel)),
        "container_os_pretty": container_pretty,
        "publication_caveat": caveat,
    }


def _host_cpu_and_ram() -> dict[str, Any]:
    cpu_model = platform.processor() or ""
    logical_cores = os.cpu_count() or 0
    ram_gib: float | None = None
    sys_low = platform.system().lower()

    if sys_low == "linux":
        cpuinfo = _read_text(Path("/proc/cpuinfo"))
        m = re.search(r"^model name\s*:\s*(.+)$", cpuinfo, re.MULTILINE)
        if m:
            cpu_model = m.group(1).strip()
        meminfo = _read_text(Path("/proc/meminfo"))
        mm = re.search(r"^MemTotal:\s*(\d+)\s*kB", meminfo, re.MULTILINE)
        if mm:
            ram_gib = round(int(mm.group(1)) / (1024.0 * 1024.0), 2)
    elif sys_low == "windows":
        ps_out = _run_cmd(
            [
                "powershell",
                "-NoProfile",
                "-Command",
                "$cpu=(Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name); "
                "$ram=(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory; "
                "Write-Output ($cpu + '|' + $ram)",
            ],
            timeout_s=10,
        )
        if "|" in ps_out:
            c_name, r_bytes = ps_out.split("|", 1)
            if c_name.strip():
                cpu_model = c_name.strip()
            try:
                ram_gib = round(int(r_bytes.strip()) / (1024.0 ** 3), 2)
            except ValueError:
                pass
    elif sys_low == "darwin":
        brand = _run_cmd(["sysctl", "-n", "machdep.cpu.brand_string"])
        if brand:
            cpu_model = brand
        mem_b = _run_cmd(["sysctl", "-n", "hw.memsize"])
        try:
            ram_gib = round(int(mem_b) / (1024.0 ** 3), 2)
        except ValueError:
            pass

    return {
        "cpu_model": cpu_model or "unknown",
        "cpu_logical_cores": logical_cores,
        "ram_total_gib": ram_gib,
    }


def _collect_docker_info(container_id: str | None = None) -> dict[str, Any]:
    info_raw = _run_cmd(["docker", "info", "--format", "{{json .}}"], timeout_s=15)
    docker_meta: dict[str, Any] = {
        "available": False,
        "server_version": "",
        "operating_system": "",
        "kernel_version": "",
        "os_type": "",
        "architecture": "",
        "ncpu": None,
        "mem_total_gib": None,
        "container_uname": "",
        "container_proc_version": "",
        "container_os_release": {},
        "container_java_version": "",
    }
    if info_raw:
        try:
            parsed = json.loads(info_raw)
            docker_meta["available"] = True
            docker_meta["server_version"] = str(parsed.get("ServerVersion", ""))
            docker_meta["operating_system"] = str(parsed.get("OperatingSystem", ""))
            docker_meta["kernel_version"] = str(parsed.get("KernelVersion", ""))
            docker_meta["os_type"] = str(parsed.get("OSType", ""))
            docker_meta["architecture"] = str(parsed.get("Architecture", ""))
            docker_meta["ncpu"] = parsed.get("NCPU")
            mem_total = parsed.get("MemTotal")
            if isinstance(mem_total, (int, float)) and mem_total > 0:
                docker_meta["mem_total_gib"] = round(float(mem_total) / (1024.0 ** 3), 2)
        except json.JSONDecodeError:
            docker_meta["server_version"] = _run_cmd(["docker", "--version"])
            docker_meta["available"] = bool(docker_meta["server_version"])

    if container_id:
        docker_meta["container_uname"] = _run_cmd(["docker", "exec", container_id, "uname", "-a"])
        docker_meta["container_proc_version"] = _run_cmd(
            ["docker", "exec", container_id, "cat", "/proc/version"]
        )
        os_rel_raw = _run_cmd(["docker", "exec", container_id, "cat", "/etc/os-release"])
        docker_meta["container_os_release"] = parse_os_release(os_rel_raw)
        jv = _run_cmd(["docker", "exec", container_id, "java", "-version"])
        docker_meta["container_java_version"] = jv.splitlines()[0] if jv else ""

    return docker_meta


def collect_environment_provenance(
    *,
    execution_target: str = "host",
    include_docker: bool = False,
    docker_image: str = "",
    container_id: str | None = None,
) -> dict[str, Any]:
    """Collect full host + optional Docker execution environment provenance."""
    host_system = platform.system()
    host_uname_obj = platform.uname()
    host_uname_str = (
        _run_cmd(["uname", "-a"])
        if host_system.lower() in ("linux", "darwin")
        else f"{host_uname_obj.system} {host_uname_obj.node} {host_uname_obj.release} {host_uname_obj.version} {host_uname_obj.machine}"
    )
    host_proc_version = _read_text(Path("/proc/version")) if host_system.lower() == "linux" else ""
    host_os_release = (
        parse_os_release(_read_text(Path("/etc/os-release")))
        if host_system.lower() == "linux"
        else {}
    )
    hw = _host_cpu_and_ram()
    host_java_raw = _run_cmd(["java", "-version"])
    host_java_version = host_java_raw.splitlines()[0] if host_java_raw else ""

    docker_meta = _collect_docker_info(container_id) if (include_docker or execution_target == "docker") else {}
    if docker_image and docker_meta:
        docker_meta["image"] = docker_image
        docker_meta["image_id"] = _run_cmd(
            ["docker", "image", "inspect", docker_image, "--format", "{{.Id}}"]
        )
        docker_meta["image_created"] = _run_cmd(
            ["docker", "image", "inspect", docker_image, "--format", "{{.Created}}"]
        )

    classification = classify_environment(
        host_system=host_system,
        host_proc_version=host_proc_version,
        host_os_release=host_os_release,
        execution_target=execution_target,
        docker_os=str(docker_meta.get("operating_system", "")),
        docker_kernel=str(docker_meta.get("kernel_version", "")),
        container_uname=str(docker_meta.get("container_uname", "")),
        container_proc_version=str(docker_meta.get("container_proc_version", "")),
        container_os_release=docker_meta.get("container_os_release")
        if isinstance(docker_meta.get("container_os_release"), dict)
        else {},
    )

    return {
        "schema": "evo.env-provenance:v1",
        "collected_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        **classification,
        "host": {
            "system": host_system,
            "release": platform.release(),
            "version": platform.version(),
            "machine": platform.machine(),
            "uname": host_uname_str,
            "os_pretty_name": host_os_release.get("PRETTY_NAME", f"{host_system} {platform.release()}"),
            "proc_version": host_proc_version,
            "python_version": platform.python_version(),
            "java_version": host_java_version,
            **hw,
        },
        "docker": docker_meta if docker_meta else None,
    }


def format_provenance_markdown(env: dict[str, Any]) -> list[str]:
    host = env.get("host") or {}
    docker = env.get("docker") or {}
    lines = [
        "## Execution Environment Provenance",
        "",
        f"- **Environment Class:** `{env.get('environment_class', 'unknown')}` "
        f"| **WSL2 Detected:** `{env.get('is_wsl2', False)}` "
        f"| **Native Ubuntu LTS:** `{env.get('is_native_ubuntu_lts', False)}`",
        f"- **Host OS / Kernel (`uname`):** `{host.get('uname', 'unknown')}`",
        f"- **Host Hardware:** `{host.get('cpu_model', 'unknown')}` "
        f"(`{host.get('cpu_logical_cores', 0)}` logical cores, `{host.get('ram_total_gib', 'unknown')}` GiB RAM)",
    ]
    if host.get("java_version"):
        lines.append(f"- **Host JVM:** `{host.get('java_version')}`")
    if docker:
        lines.append(
            f"- **Docker Runtime:** `Docker {docker.get('server_version', '?')}` | "
            f"OS=`{docker.get('operating_system', '?')}` | "
            f"Kernel=`{docker.get('kernel_version', '?')}` | "
            f"vCPU=`{docker.get('ncpu', '?')}` | RAM=`{docker.get('mem_total_gib', '?')}` GiB"
        )
        if docker.get("container_uname"):
            lines.append(f"- **Container `uname -a`:** `{docker.get('container_uname')}`")
        if docker.get("container_java_version"):
            lines.append(f"- **Container JVM:** `{docker.get('container_java_version')}`")
        if docker.get("image"):
            lines.append(
                f"- **Container Image:** `{docker.get('image')}` (`{docker.get('image_id', '')}`)"
            )
    caveat = env.get("publication_caveat", "")
    if caveat:
        lines.append(f"- **Honest Environment Disclosure:** {caveat}")
    lines.append("")
    return lines
