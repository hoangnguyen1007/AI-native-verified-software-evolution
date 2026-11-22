#!/usr/bin/env python3
"""
Isolated QEMU-in-Docker candidate build, test, and bytecode dependency census orchestrator
for arbitrary multi-module Maven Java repositories (captured-repair.vm-capture:v1).
"""

import argparse
import base64
import hashlib
import json
import os
import posixpath
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

VERSION = "captured-repair.vm-capture:v1"
DEFAULT_BASE_IMAGE = "eclipse-temurin@sha256:82801f9dcdcbad5a05082aba8c86d1da5b72ccb498e71318b1f91b5a477887b3"
CONTROLS = ("Dockerfile", "guest-init.sh", "run.py", "ClassDependencyCensus.java")
MEMORY = 4294967296  # 4 GiB
PIDS = 256
CPUS = 2000000000    # 2 CPUs in nano-cpus
MAVEN_CORE_GROUPS = (
    "org/apache/maven",
    "org/codehaus/plexus",
    "org/eclipse/sisu",
    "org/eclipse/aether",
    "org/sonatype/plexus",
)
SKIP_TREE_DIRS = {".git", "target", "build", ".gradle", ".idea", "node_modules", ".mvn/wrapper"}


def canonical_json(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_bytes(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return "sha256:" + h.hexdigest()


def normalize_eol(text: str) -> bytes:
    return text.replace("\r\n", "\n").encode("utf-8")


def apply_unified_patch_line_safe(original_text: str, patch_text: str, target_rel: str) -> str:
    lines = patch_text.splitlines(keepends=False)
    sections = {}
    current_path = None
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("--- "):
            a_path = line[4:].strip().strip('"')
            if a_path.startswith("a/"):
                a_path = a_path[2:]
            i += 1
            if i < len(lines) and lines[i].startswith("+++ "):
                b_path = lines[i][4:].strip().strip('"')
                if b_path.startswith("b/"):
                    b_path = b_path[2:]
                current_path = b_path if b_path != "/dev/null" else a_path
                sections.setdefault(current_path, [])
                i += 1
                continue
        if line.startswith("@@ ") and current_path is not None:
            m = re.match(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", line)
            if not m:
                raise ValueError(f"Invalid hunk header: {line}")
            old_start = int(m.group(1))
            old_count = int(m.group(2)) if m.group(2) is not None else 1
            new_count = int(m.group(4)) if m.group(4) is not None else 1
            hunk_lines = []
            i += 1
            old_seen = 0
            new_seen = 0
            while i < len(lines):
                hl = lines[i]
                if hl == r"\ No newline at end of file":
                    hunk_lines.append((hl, ""))
                    i += 1
                    continue
                if old_seen == old_count and new_seen == new_count:
                    break
                if not hl:
                    raise ValueError("Unexpected blank line in hunk")
                prefix = hl[0]
                if prefix == " ":
                    old_seen += 1
                    new_seen += 1
                elif prefix == "-":
                    old_seen += 1
                elif prefix == "+":
                    new_seen += 1
                else:
                    raise ValueError(f"Unsupported patch line: {hl}")
                hunk_lines.append((prefix, hl[1:]))
                i += 1
            sections[current_path].append((old_start, old_count, hunk_lines))
            continue
        i += 1

    if target_rel not in sections:
        raise ValueError(f"Patch does not contain {target_rel}")
    newline = "\r\n" if "\r\n" in original_text else "\n"
    ends_with_nl = original_text.endswith("\n") or original_text.endswith("\r")
    src_lines = []
    if original_text:
        for raw_line in original_text.split("\n"):
            src_lines.append(raw_line[:-1] if raw_line.endswith("\r") else raw_line)
        if ends_with_nl:
            src_lines.pop()

    out_lines = []
    cursor = 0
    trailing_nl = ends_with_nl
    for old_start, old_count, hunk_lines in sections[target_rel]:
        expected_idx = 0 if old_count == 0 and old_start == 0 else old_start - 1
        while cursor < expected_idx:
            out_lines.append(src_lines[cursor])
            cursor += 1
        last_removed_no_nl = False
        for tag, content in hunk_lines:
            if tag == " ":
                if cursor >= len(src_lines) or src_lines[cursor] != content:
                    raise ValueError(f"Context mismatch in {target_rel}")
                out_lines.append(content)
                cursor += 1
            elif tag == "-":
                if cursor >= len(src_lines) or src_lines[cursor] != content:
                    raise ValueError(f"Removal mismatch in {target_rel}")
                cursor += 1
                last_removed_no_nl = True
            elif tag == "+":
                out_lines.append(content)
                last_removed_no_nl = False
                trailing_nl = True
            elif tag == r"\ No newline at end of file":
                if not last_removed_no_nl:
                    trailing_nl = False
    while cursor < len(src_lines):
        out_lines.append(src_lines[cursor])
        cursor += 1
    if not out_lines:
        return ""
    return newline.join(out_lines) + (newline if trailing_nl else "")


def strip_ns(tag: str) -> str:
    return tag.split("}", 1)[-1]


def parse_pom_once(pom_path: Path):
    try:
        root = ET.fromstring(pom_path.read_text(encoding="utf-8", errors="replace"))
    except ET.ParseError:
        return {}, None, [], []
    props: dict[str, str] = {}
    g = a = v = None
    parent = None
    for child in root:
        t = strip_ns(child.tag)
        if t == "groupId" and child.text:
            g = child.text.strip()
        elif t == "artifactId" and child.text:
            a = child.text.strip()
        elif t == "version" and child.text:
            v = child.text.strip()
        elif t == "parent":
            pg = pa = pv = None
            for pchild in child:
                pt = strip_ns(pchild.tag)
                if pt == "groupId" and pchild.text:
                    pg = pchild.text.strip()
                elif pt == "artifactId" and pchild.text:
                    pa = pchild.text.strip()
                elif pt == "version" and pchild.text:
                    pv = pchild.text.strip()
            if pg and pa and pv:
                parent = (pg, pa, pv)
        elif t == "properties":
            for pe in child:
                if pe.text is not None:
                    props[strip_ns(pe.tag)] = pe.text.strip()
    if parent:
        g = g or parent[0]
        v = v or parent[2]
    if g:
        props["project.groupId"] = g
    if a:
        props["project.artifactId"] = a
    if v:
        props["project.version"] = v
        props.setdefault("revision", v)

    raw_mgmt = []
    raw_deps = []
    in_mgmt = set()
    for mgmt_tag in ("dependencyManagement", "pluginManagement"):
        for mgmt in root.iter():
            if strip_ns(mgmt.tag) == mgmt_tag:
                for elem in mgmt.iter():
                    if elem is not mgmt:
                        in_mgmt.add(id(elem))
                    tag = strip_ns(elem.tag)
                    if tag in ("dependency", "plugin"):
                        fields = {strip_ns(s.tag): s.text.strip() for s in elem if s.text is not None}
                        if fields.get("groupId") and fields.get("artifactId"):
                            raw_mgmt.append((fields["groupId"], fields["artifactId"], fields.get("version"), fields.get("scope", "")))
    for elem in root.iter():
        tag = strip_ns(elem.tag)
        if tag in ("dependency", "plugin", "extension"):
            fields = {strip_ns(s.tag): s.text.strip() for s in elem if s.text is not None}
            if fields.get("groupId") and fields.get("artifactId"):
                raw_deps.append((fields["groupId"], fields["artifactId"], fields.get("version"), fields.get("scope", ""), id(elem) in in_mgmt))
    return props, parent, raw_mgmt, raw_deps


def stage_m2_subset(repo_dir: Path, m2_root: Path, m2_stage: Path):
    if not m2_root.is_dir():
        return
    visited_poms: set[Path] = set()
    specs: dict[Path, tuple[dict[str, str], list, list]] = {}
    staged_gavs: set[tuple[str, str, str]] = set()
    shared_props: dict[str, str] = {}
    managed: dict[tuple[str, str], str] = {}
    queue: list[Path] = sorted(
        (p for p in repo_dir.rglob("pom.xml") if not any(part in SKIP_TREE_DIRS for part in p.relative_to(repo_dir).parts)),
        key=lambda p: (len(p.relative_to(repo_dir).parts), str(p)),
    )

    def enqueue_gav(g: str | None, a: str | None, v: str | None) -> bool:
        if not (g and a and v) or "${" in g + a + v or v in ("LATEST", "RELEASE"):
            return False
        if (g, a, v) in staged_gavs:
            return False
        src = m2_root / Path(*g.split(".")) / a / v
        if not src.is_dir():
            return False
        staged_gavs.add((g, a, v))
        dst = m2_stage / Path(*g.split(".")) / a / v
        if not dst.exists():
            dst.mkdir(parents=True, exist_ok=True)
            for item in src.iterdir():
                if item.is_file() and not item.name.startswith("_remote.repositories") and not item.name.endswith(".lastUpdated"):
                    shutil.copy2(item, dst / item.name)
        for p in dst.glob("*.pom"):
            if p not in visited_poms:
                queue.append(p)
        return True

    def res(val: str | None, loc: dict[str, str]) -> str | None:
        if not val:
            return val
        for _ in range(5):
            if "${" not in val:
                break
            val = re.sub(r"\$\{([^}]+)\}", lambda m: loc.get(m.group(1), m.group(0)), val)
        return val

    def evaluate_spec(own_p: dict[str, str], raw_mgmt: list, raw_deps: list) -> bool:
        added = False
        loc = {**shared_props, **own_p}
        for mg, ma, mv, ms in raw_mgmt:
            rg, ra, rv = res(mg, loc), res(ma, loc), res(mv, loc)
            if rg and ra and rv and "${" not in rg + ra + rv:
                managed.setdefault((rg, ra), rv)
                if ms == "import" and enqueue_gav(rg, ra, rv):
                    added = True
        for dg, da, dv, ds, in_m in raw_deps:
            if in_m and ds != "import":
                continue
            rg, ra, rv = res(dg, loc), res(da, loc), res(dv, loc)
            if rg and ra and "${" not in rg + ra:
                vv = rv if (rv and "${" not in rv) else managed.get((rg, ra))
                if enqueue_gav(rg, ra, vv):
                    added = True
        return added

    while True:
        progressed = False
        while queue:
            pom = queue.pop(0)
            if pom in visited_poms or not pom.is_file():
                continue
            visited_poms.add(pom)
            own_p, parent, raw_mgmt, raw_deps = parse_pom_once(pom)
            for k, val in own_p.items():
                if not k.startswith("project."):
                    shared_props.setdefault(k, val)
            specs[pom] = (own_p, raw_mgmt, raw_deps)
            if parent and enqueue_gav(*parent):
                progressed = True
            if evaluate_spec(own_p, raw_mgmt, raw_deps):
                progressed = True
        for own_p, raw_mgmt, raw_deps in list(specs.values()):
            if evaluate_spec(own_p, raw_mgmt, raw_deps):
                progressed = True
        if not progressed and not queue:
            break

    for group_rel in MAVEN_CORE_GROUPS:
        src = m2_root / group_rel
        if not src.is_dir():
            continue
        for item in src.rglob("*"):
            if item.is_file() and not item.name.startswith("_remote.repositories") and not item.name.endswith(".lastUpdated"):
                rel = item.relative_to(m2_root)
                dst = m2_stage / rel
                if not dst.exists():
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(item, dst)


def collect_controls(script_dir: Path) -> dict[str, str]:
    controls = {}
    for name in CONTROLS:
        path = script_dir / name
        if not path.is_file():
            raise FileNotFoundError(f"Missing control resource: {path}")
        raw = path.read_text(encoding="utf-8")
        controls[name] = sha256_bytes(normalize_eol(raw))
    return controls


def resolve_proposal_and_plan(proposal_arg: Path, verification_arg: Path):
    raw_prop = json.loads(proposal_arg.read_text(encoding="utf-8"))
    if "proposal" in raw_prop and isinstance(raw_prop["proposal"], dict):
        proposal_obj = raw_prop["proposal"]
    elif "identity" in raw_prop and "candidate" in raw_prop:
        proposal_obj = raw_prop
    else:
        raise ValueError("Invalid proposal JSON")
    proposal_id = proposal_obj["identity"]
    candidate = proposal_obj["candidate"]
    raw_ver = json.loads(verification_arg.read_text(encoding="utf-8"))
    if "data" in raw_ver and isinstance(raw_ver["data"], dict) and "plan" in raw_ver["data"]:
        raw_ver = raw_ver["data"]
    plan_id = raw_ver["planIdentity"]
    plan_obj = raw_ver["plan"]
    if plan_obj["proposal"] != proposal_id:
        raise ValueError("Verification plan proposal mismatch")
    affected_modules = plan_obj.get("affectedModules", [])
    return proposal_id, plan_id, candidate, affected_modules


def docker_cmd(docker_bin: list[str], *args, timeout=60) -> str:
    return subprocess.check_output([*docker_bin, *args], text=True, encoding="utf-8", errors="replace", timeout=timeout).strip()


def run_isolated_container(docker_bin: list[str], image: str) -> tuple[str, str]:
    container = docker_cmd(
        docker_bin,
        "create",
        "--pull=never",
        "--network=none",
        "--read-only",
        "--cap-drop=ALL",
        "--security-opt=no-new-privileges",
        f"--memory={MEMORY}",
        f"--memory-swap={MEMORY}",
        "--cpus=2",
        f"--pids-limit={PIDS}",
        "--log-driver=local",
        "--log-opt=max-size=4m",
        "--log-opt=max-file=1",
        "--log-opt=compress=false",
        image,
    )
    if not re.fullmatch(r"[a-f0-9]{64}", container):
        raise RuntimeError(f"CONTAINER_ID_UNAVAILABLE: {container}")
    try:
        config = json.loads(docker_cmd(docker_bin, "inspect", container))[0]
        host = config["HostConfig"]
        if (
            config["Image"] != image
            or config["Mounts"]
            or host["NetworkMode"] != "none"
            or not host["ReadonlyRootfs"]
            or host["Privileged"]
            or host["CapDrop"] != ["ALL"]
            or host["SecurityOpt"] != ["no-new-privileges"]
            or host["Memory"] != MEMORY
            or host["MemorySwap"] != MEMORY
            or host["PidsLimit"] != PIDS
            or host["NanoCpus"] != CPUS
        ):
            raise RuntimeError("WORKER_POLICY_MISMATCH")
        docker_cmd(docker_bin, "start", container, timeout=60)
        exit_code = docker_cmd(docker_bin, "wait", container, timeout=930)
        state = json.loads(docker_cmd(docker_bin, "inspect", "--format", "{{json .State}}", container))
        log = docker_cmd(docker_bin, "logs", container, timeout=120)
        if exit_code != "0" or state["Status"] != "exited" or state["OOMKilled"]:
            raise RuntimeError(f"GUEST_EXIT_FAILED exit={exit_code} state={state}\n{log[-2000:]}")
        return container, log
    finally:
        subprocess.run([*docker_bin, "rm", "--force", container], capture_output=True)


def main():
    parser = argparse.ArgumentParser(description="Isolated QEMU-in-Docker repair verifier")
    parser.add_argument("--repo", required=True, type=Path, help="Local repository checkout directory")
    parser.add_argument("--proposal", required=True, type=Path, help="Captured repair proposal or outcome JSON")
    parser.add_argument("--verification", required=True, type=Path, help="Verification result/plan JSON")
    parser.add_argument("--m2", required=True, type=Path, help="Local Maven repository (~/.m2/repository)")
    parser.add_argument("--maven-zip", required=True, type=Path, help="Pinned apache-maven-*-bin.zip")
    parser.add_argument("--out", required=True, type=Path, help="Output capture directory")
    parser.add_argument("--base", default=DEFAULT_BASE_IMAGE, help="Pinned eclipse-temurin digest")
    parser.add_argument("--docker-context", default="desktop-linux", help="Docker CLI context (empty for default)")
    args = parser.parse_args()

    if not re.fullmatch(r"(docker\.io/library/)?eclipse-temurin@sha256:[a-f0-9]{64}", args.base):
        raise ValueError("UNPINNED_BASE_IMAGE")

    docker_bin = ["docker", "--context", args.docker_context] if args.docker_context else ["docker"]
    if args.docker_context == "desktop-linux":
        endpoint = json.loads(docker_cmd(docker_bin, "context", "inspect", "desktop-linux"))[0]["Endpoints"]["docker"]["Host"]
        if endpoint != "npipe:////./pipe/dockerDesktopLinuxEngine":
            raise RuntimeError(f"LOCAL_DOCKER_DESKTOP_REQUIRED: {endpoint}")

    script_dir = Path(__file__).resolve().parent
    controls = collect_controls(script_dir)
    proposal_id, plan_id, candidate, affected_modules = resolve_proposal_and_plan(args.proposal, args.verification)

    out_dir = args.out.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    ctx_dir = out_dir / "context"
    if ctx_dir.exists():
        shutil.rmtree(ctx_dir)
    inputs_dir = ctx_dir / "inputs"
    cand_dir = inputs_dir / "candidate"
    m2_stage = inputs_dir / "m2"
    cand_dir.mkdir(parents=True, exist_ok=True)
    m2_stage.mkdir(parents=True, exist_ok=True)

    repo_src = args.repo.resolve()
    for root, dirs, files in os.walk(repo_src):
        rel_root = Path(root).relative_to(repo_src)
        dirs[:] = [d for d in sorted(dirs) if d not in SKIP_TREE_DIRS and "/".join((*rel_root.parts, d)) not in SKIP_TREE_DIRS]
        for fname in sorted(files):
            src_file = Path(root) / fname
            dst_file = cand_dir / rel_root / fname
            dst_file.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src_file, dst_file)

    patch_text = candidate["patch"]
    for edit in candidate["edits"]:
        rel_path = edit["path"]
        target_file = cand_dir / Path(*rel_path.split("/"))
        if not target_file.is_file():
            raise FileNotFoundError(f"Edited file missing from repo copy: {rel_path}")
        raw_bytes = target_file.read_bytes()
        if sha256_bytes(raw_bytes) != edit["before"]:
            raise ValueError(f"Baseline file digest mismatch for {rel_path}")
        encoding = "ascii"
        try:
            raw_bytes.decode("ascii")
        except UnicodeDecodeError:
            encoding = "utf-8"
        orig_text = raw_bytes.decode(encoding)
        updated_text = apply_unified_patch_line_safe(orig_text, patch_text, rel_path)
        updated_bytes = updated_text.encode("utf-8" if encoding == "utf-8" else "ascii")
        if sha256_bytes(updated_bytes) != edit["after"]:
            updated_bytes = updated_text.encode("utf-8")
            if sha256_bytes(updated_bytes) != edit["after"]:
                raise ValueError(f"Patched file digest mismatch for {rel_path}")
        target_file.write_bytes(updated_bytes)

    stage_m2_subset(cand_dir, args.m2.resolve(), m2_stage)

    shutil.copy2(script_dir / "ClassDependencyCensus.java", inputs_dir / "ClassDependencyCensus.java")
    mvn_pl_list = [
        m for m in affected_modules
        if m and m != "." and not m.startswith("module:") and (cand_dir / Path(*m.split("/")) / "pom.xml").is_file()
    ]
    if not mvn_pl_list:
        seen_mods = set()
        for edit in candidate["edits"]:
            rel_parts = Path(*edit["path"].split("/")).parts
            for end in range(len(rel_parts) - 1, 0, -1):
                candidate_mod = posixpath.join(*rel_parts[:end])
                if (cand_dir / Path(*rel_parts[:end]) / "pom.xml").is_file():
                    seen_mods.add(candidate_mod)
                    break
        mvn_pl_list = sorted(seen_mods)
    build_spec = f"EVO_BUILD_TOOL=maven\nEVO_MVN_PL={','.join(mvn_pl_list)}\n"
    (inputs_dir / "build-spec.env").write_text(build_spec, encoding="utf-8", newline="\n")

    manifest_entries = []
    for fpath in sorted(inputs_dir.rglob("*")):
        if fpath.is_file():
            logical = posixpath.join(*fpath.relative_to(inputs_dir).parts)
            manifest_entries.append((logical, sha256_file(fpath)[7:]))
    manifest_entries.sort(key=lambda x: x[0])
    manifest_bytes = ("\n".join(f"{hex_d}  {logical}" for logical, hex_d in manifest_entries) + "\n").encode("utf-8")
    (inputs_dir / "manifest.sha256").write_bytes(manifest_bytes)
    inputs_digest = sha256_bytes(manifest_bytes)

    for fname in ("Dockerfile", "guest-init.sh"):
        raw = (script_dir / fname).read_text(encoding="utf-8")
        (ctx_dir / fname).write_bytes(normalize_eol(raw))
    shutil.copy2(args.maven_zip.resolve(), ctx_dir / "apache-maven-bin.zip")

    iid_file = out_dir / "image-id"
    subprocess.run(
        [*docker_bin, "build", "--platform=linux/amd64", "--build-arg", f"BASE={args.base}",
         "--iidfile", str(iid_file), str(ctx_dir)],
        check=True,
        timeout=900,
    )
    (ctx_dir / "apache-maven-bin.zip").unlink(missing_ok=True)
    image_id = iid_file.read_text(encoding="utf-8").strip()
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", image_id):
        raise RuntimeError(f"IMAGE_ID_UNAVAILABLE: {image_id}")

    container_id, raw_serial = run_isolated_container(docker_bin, image_id)
    serial_text = raw_serial.replace("\r\n", "\n")
    if not serial_text.endswith("\n"):
        serial_text += "\n"
    (out_dir / "serial.txt").write_bytes(serial_text.encode("utf-8"))

    observed = [line for line in serial_text.splitlines() if line.startswith("EVO_")]
    compile_exit = None
    test_exit = None
    tests_run = tests_failed = tests_errors = 0
    census_digest_line = None
    b64_lines = []
    checks = []
    in_b64 = False
    for line in serial_text.splitlines():
        if line == "CENSUS_B64_BEGIN":
            in_b64 = True
        elif line == "CENSUS_B64_END":
            in_b64 = False
        elif in_b64 and line.startswith("CENSUS_B64:"):
            b64_lines.append(line[len("CENSUS_B64:") :])
        elif line.startswith("EVO_COMPILE_EXIT "):
            compile_exit = int(line.split(" ", 1)[1].strip())
        elif line.startswith("EVO_TEST_EXIT "):
            test_exit = int(line.split(" ", 1)[1].strip())
        elif line.startswith("EVO_TESTS "):
            m = re.match(r"EVO_TESTS run=(\d+) failed=(\d+) errors=(\d+)", line)
            if m:
                tests_run, tests_failed, tests_errors = int(m.group(1)), int(m.group(2)), int(m.group(3))
        elif line.startswith("EVO_CLASS_CENSUS "):
            census_digest_line = line.split(" ", 1)[1].strip()
        elif line.startswith("EVO_CHECK "):
            checks.append(line.split(" ", 1)[1].strip())

    if compile_exit is None or test_exit is None or census_digest_line is None or not b64_lines:
        raise RuntimeError(f"Incomplete VM serial output:\n{serial_text[-2000:]}")

    census_raw = base64.b64decode("".join(b64_lines))
    census_obj = json.loads(census_raw.decode("utf-8"))
    census_canonical_digest = sha256_bytes(canonical_json(census_obj).encode("utf-8"))
    if census_canonical_digest != census_digest_line:
        raise ValueError(f"Census digest mismatch: {census_canonical_digest} vs {census_digest_line}")

    (out_dir / "class-census.json").write_bytes(census_raw)
    capture_doc = {
        "version": VERSION,
        "proposal": proposal_id,
        "plan": plan_id,
        "inputs": inputs_digest,
        "controls": controls,
        "base": args.base if args.base.startswith("eclipse-temurin@") else DEFAULT_BASE_IMAGE,
        "image": image_id,
        "container": container_id,
        "buildTool": "maven",
        "modules": affected_modules if affected_modules else ["."],
        "compileExit": compile_exit,
        "testExit": test_exit,
        "testsRun": tests_run,
        "testsFailed": tests_failed,
        "testsErrors": tests_errors,
        "classCensusDigest": census_canonical_digest,
        "classCensus": census_obj,
        "observed": observed,
        "checks": checks,
        "serial": sha256_bytes(serial_text.encode("utf-8")),
    }
    capture_bytes = canonical_json(capture_doc).encode("utf-8")
    (out_dir / "capture.json").write_bytes(capture_bytes)
    capture_digest = sha256_bytes(capture_bytes)
    print(canonical_json({
        "capture": str(out_dir / "capture.json"),
        "digest": capture_digest,
        "compileExit": compile_exit,
        "testExit": test_exit,
        "testsRun": tests_run,
        "classCount": census_obj["classCount"],
    }))


if __name__ == "__main__":
    main()
