#!/usr/bin/env python3
"""
Isolated candidate build, test, behavioral-diff, and dependency census orchestrator
for multi-module Maven/Gradle Java and polyglot (Node/TypeScript, Python, Go) repositories
(captured-repair.vm-capture:v1).
Supports:
  - qemu-docker (default for Java): networkless QEMU guest VM inside hardened read-only Docker container
  - docker-container: hardened --network=none --read-only --cap-drop=ALL Docker container (explicitly labeled)
NEVER executes target builds or tests directly on the host.
"""

import argparse
import base64
import hashlib
import json
import os
import posixpath
import re
import secrets
import shlex
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

VERSION = "captured-repair.vm-capture:v1"
DEFAULT_BASE_IMAGE = "eclipse-temurin@sha256:82801f9dcdcbad5a05082aba8c86d1da5b72ccb498e71318b1f91b5a477887b3"
PINNED_BASE_RE = re.compile(r"^(docker\.io/library/)?(eclipse-temurin|node|python|golang)@sha256:[a-f0-9]{64}$")
MAVEN_COORD_SEGMENT_RE = re.compile(r"^[A-Za-z0-9._-]+$")
CONTROL_NONCE_RE = re.compile(r"^[a-f0-9]{32}$")
NON_ROOT_USER = "65532:65532"
CONTROLS = ("Dockerfile", "guest-init.sh", "run.py", "ClassDependencyCensus.java")
EMPTY_FILE_SHA256 = "sha256:" + hashlib.sha256(b"").hexdigest()
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
SKIP_TREE_DIRS = {".git", "target", "build", ".gradle", ".idea", "node_modules", ".mvn/wrapper", "__pycache__", ".venv", "venv"}
GRADLE_SKIP_FILES = {"gc.properties"}
GRADLE_SKIP_PREFIXES = ("journal-",)
GRADLE_SKIP_SUFFIXES = (".lock", ".lck")


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


def _win_long_path(path: Path) -> str:
    s = str(path.resolve(strict=False))
    if os.name == "nt" and not s.startswith("\\\\?\\"):
        if s.startswith("\\\\"):
            return "\\\\?\\UNC\\" + s[2:]
        return "\\\\?\\" + s
    return s


def safe_rmtree(path: Path) -> None:
    """Removes a directory tree safely on all OSes, including Windows MAX_PATH (>260 chars) & read-only files."""
    target_str = _win_long_path(path) if os.name == "nt" else str(path)
    if not os.path.lexists(target_str):
        return
    if os.path.islink(target_str) or not os.path.isdir(target_str):
        try:
            os.chmod(target_str, 0o700)
        except OSError:
            pass
        os.unlink(target_str)
        return

    def _handle_readonly(func, fpath, _exc):
        try:
            os.chmod(fpath, 0o700)
            func(fpath)
        except OSError:
            pass

    if sys.version_info >= (3, 12):
        shutil.rmtree(target_str, onexc=_handle_readonly)
    else:
        shutil.rmtree(target_str, onerror=_handle_readonly)


def has_symlink_component(path: Path, stop_at: Path, stop_norm: Path | None = None) -> bool:
    """Returns True if path or any existing parent between path and stop_at (inclusive) is a symlink."""
    if stop_norm is None:
        try:
            stop_norm = stop_at.resolve()
        except OSError:
            return True
    cur = path
    while True:
        if cur.is_symlink():
            return True
        if cur == stop_at or cur == stop_norm:
            break
        parent = cur.parent
        if parent == cur:
            break
        cur = parent
    return False


def is_valid_maven_coord(g: str | None, a: str | None, v: str | None) -> bool:
    if not (isinstance(g, str) and isinstance(a, str) and isinstance(v, str)):
        return False
    if not (g and a and v):
        return False
    combined = g + a + v
    if "${" in combined or "\x00" in combined or "/" in combined or "\\" in combined or ":" in combined:
        return False
    if v in ("LATEST", "RELEASE", ".", "..") or a in (".", "..") or g in (".", ".."):
        return False
    if ".." in g:
        return False
    if not (MAVEN_COORD_SEGMENT_RE.fullmatch(g) and MAVEN_COORD_SEGMENT_RE.fullmatch(a) and MAVEN_COORD_SEGMENT_RE.fullmatch(v)):
        return False
    g_parts = g.split(".")
    if not g_parts or any(not seg or seg in (".", "..") for seg in g_parts):
        return False
    return True


def resolve_safe_relative_path(base_dir: Path, rel_path: str, error_code: str) -> Path:
    if (
        not isinstance(rel_path, str)
        or not rel_path
        or rel_path.startswith("/")
        or "\\" in rel_path
        or ":" in rel_path
        or "\x00" in rel_path
    ):
        raise ValueError(f"{error_code}: {rel_path}")
    parts = rel_path.split("/")
    if any(not p or p in (".", "..") for p in parts):
        raise ValueError(f"{error_code}: {rel_path}")
    base_resolved = base_dir.resolve()
    target = base_dir / Path(*parts)
    if has_symlink_component(target, base_dir):
        raise ValueError(f"{error_code}: {rel_path}")
    target_resolved = target.resolve(strict=False)
    if not target_resolved.is_relative_to(base_resolved) or target_resolved == base_resolved:
        raise ValueError(f"{error_code}: {rel_path}")
    return target


def stage_repo_tree(repo_src: Path, cand_dir: Path) -> None:
    """Stages repository files without dereferencing host symlinks or escaping repo_src."""
    repo_resolved = repo_src.resolve()
    for root, dirs, files in os.walk(repo_resolved, followlinks=False):
        root_path = Path(root)
        rel_root = root_path.relative_to(repo_resolved)
        safe_dirs = []
        for d in sorted(dirs):
            if d in SKIP_TREE_DIRS or "/".join((*rel_root.parts, d)) in SKIP_TREE_DIRS:
                continue
            d_path = root_path / d
            if d_path.is_symlink():
                continue
            try:
                if not d_path.resolve().is_relative_to(repo_resolved):
                    continue
            except OSError:
                continue
            safe_dirs.append(d)
        dirs[:] = safe_dirs
        for fname in sorted(files):
            src_file = root_path / fname
            if src_file.is_symlink() or not src_file.is_file():
                continue
            try:
                if not src_file.resolve().is_relative_to(repo_resolved):
                    continue
            except OSError:
                continue
            dst_file = cand_dir / rel_root / fname
            dst_file.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src_file, dst_file)


def instantiate_guest_init(raw_gi: str, ctrl_nonce: str) -> str:
    """Instantiates guest-init.sh with the per-run control nonce exactly once without clobbering fallback checks."""
    if not CONTROL_NONCE_RE.fullmatch(ctrl_nonce):
        raise ValueError("INVALID_CONTROL_NONCE")
    if raw_gi.count("__EVO_CTRL_PREFIX__") != 1:
        raise ValueError("INVALID_GUEST_INIT_TEMPLATE")
    instantiated = raw_gi.replace("__EVO_CTRL_PREFIX__", f"EVO_CTRL_{ctrl_nonce}:", 1)
    if "__EVO_CTRL_PREFIX__" in instantiated:
        raise ValueError("INVALID_GUEST_INIT_TEMPLATE")
    return instantiated


def is_safe_mvn_module_path(mod: str) -> bool:
    if (
        not isinstance(mod, str)
        or not mod
        or mod.startswith("-")
        or mod.startswith("/")
        or ":" in mod
        or "\\" in mod
        or "\x00" in mod
    ):
        return False
    if not re.fullmatch(r"[A-Za-z0-9._/-]+", mod):
        return False
    parts = mod.split("/")
    return not any(p in ("", ".", "..") for p in parts)


def stage_node_repo_modules(repo_src: Path, dst_dir: Path) -> bool:
    """Stages in-repo node_modules only if it is a real non-symlink directory contained within repo_src."""
    nm = repo_src / "node_modules"
    if nm.is_symlink() or not nm.is_dir():
        return False
    repo_res = repo_src.resolve()
    try:
        nm_res = nm.resolve()
    except OSError:
        return False
    if not nm_res.is_relative_to(repo_res):
        return False
    stage_directory_bounded(nm, dst_dir)
    return True


def parse_unified_patch_sections(patch_text: str):
    lines = patch_text.splitlines(keepends=False)
    sections = {}
    ops = {}
    current_path = None
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("--- "):
            a_raw = line[4:].strip().strip('"')
            a_path = a_raw[2:] if a_raw.startswith("a/") else a_raw
            i += 1
            if i < len(lines) and lines[i].startswith("+++ "):
                b_raw = lines[i][4:].strip().strip('"')
                b_path = b_raw[2:] if b_raw.startswith("b/") else b_raw
                if a_raw == "/dev/null":
                    current_path = b_path
                    ops[current_path] = "CREATE"
                elif b_raw == "/dev/null":
                    current_path = a_path
                    ops[current_path] = "DELETE"
                else:
                    current_path = b_path
                    ops[current_path] = "MODIFY"
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
    return sections, ops


def apply_unified_patch_line_safe(original_text: str, patch_text: str, target_rel: str) -> str:
    sections, _ = parse_unified_patch_sections(patch_text)
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
    trailing_nl = ends_with_nl if original_text else True
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
    if pom_path.is_symlink() or not pom_path.is_file():
        return {}, None, None, [], []
    try:
        root = ET.fromstring(pom_path.read_text(encoding="utf-8", errors="replace"))
    except (ET.ParseError, OSError):
        return {}, None, None, [], []
    props: dict[str, str] = {}
    g = a = v = None
    parent = None
    relative_path = None
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
                elif pt == "relativePath" and pchild.text is not None:
                    relative_path = pchild.text.strip()
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
            group_id = fields.get("groupId")
            if not group_id and tag == "plugin":
                group_id = "org.apache.maven.plugins"
            if group_id and fields.get("artifactId"):
                raw_deps.append((group_id, fields["artifactId"], fields.get("version"), fields.get("scope", ""), id(elem) in in_mgmt))
    return props, parent, relative_path, raw_mgmt, raw_deps


def stage_m2_subset(repo_dir: Path, m2_root: Path, m2_stage: Path):
    if not m2_root.is_dir() or m2_root.is_symlink():
        return
    repo_root_res = repo_dir.resolve()
    m2_root_res = m2_root.resolve()
    m2_stage_res = m2_stage.resolve()
    visited_poms: set[Path] = set()
    specs: dict[Path, tuple[dict[str, str], list, list]] = {}
    staged_gavs: set[tuple[str, str, str]] = set()
    gav_cache: dict[tuple[str, str, str], Path | None] = {}
    shared_props: dict[str, str] = {}
    managed: dict[tuple[str, str], set[str]] = {}
    mgmt_templates: list[tuple[str, str, str, str]] = []
    mgmt_templates_seen: set[tuple[str, str, str, str]] = set()

    discovered_poms: list[Path] = []
    for root, dirs, files in os.walk(repo_root_res, followlinks=False):
        root_p = Path(root)
        rel_root = root_p.relative_to(repo_root_res)
        dirs[:] = [
            d for d in sorted(dirs)
            if d not in SKIP_TREE_DIRS
            and "/".join((*rel_root.parts, d)) not in SKIP_TREE_DIRS
            and not (root_p / d).is_symlink()
        ]
        if "pom.xml" in files:
            pom_cand = root_p / "pom.xml"
            if not pom_cand.is_symlink() and pom_cand.is_file():
                try:
                    if pom_cand.resolve().is_relative_to(repo_root_res):
                        discovered_poms.append(pom_cand)
                except OSError:
                    pass
    queue: list[Path] = sorted(
        discovered_poms,
        key=lambda p: (len(p.relative_to(repo_root_res).parts), str(p)),
    )

    def stage_gav_dir(g: str | None, a: str | None, v: str | None) -> Path | None:
        if not is_valid_maven_coord(g, a, v):
            return None
        assert g is not None and a is not None and v is not None
        key = (g, a, v)
        if key in gav_cache:
            return gav_cache[key]
        rel_gav = Path(*g.split(".")) / a / v
        src = m2_root / rel_gav
        if not src.exists():
            gav_cache[key] = None
            return None
        if has_symlink_component(src, m2_root, m2_root_res):
            gav_cache[key] = None
            return None
        try:
            src_res = src.resolve()
        except OSError:
            gav_cache[key] = None
            return None
        if not src_res.is_relative_to(m2_root_res) or not src_res.is_dir():
            gav_cache[key] = None
            return None
        dst = m2_stage / rel_gav
        if has_symlink_component(dst, m2_stage, m2_stage_res):
            gav_cache[key] = None
            return None
        try:
            dst_res = dst.resolve(strict=False)
        except OSError:
            gav_cache[key] = None
            return None
        if not dst_res.is_relative_to(m2_stage_res):
            gav_cache[key] = None
            return None
        if not dst.exists():
            dst.mkdir(parents=True, exist_ok=True)
            for item in src_res.iterdir():
                if (
                    item.is_symlink()
                    or not item.is_file()
                    or item.name.startswith("_remote.repositories")
                    or item.name.endswith(".lastUpdated")
                ):
                    continue
                try:
                    if not item.resolve().is_relative_to(m2_root_res):
                        continue
                except OSError:
                    continue
                dst_item = dst / item.name
                if dst_item.is_symlink() or not dst_item.resolve(strict=False).is_relative_to(m2_stage_res):
                    continue
                shutil.copy2(item, dst_item)
        gav_cache[key] = dst
        return dst

    def enqueue_gav(g: str | None, a: str | None, v: str | None) -> bool:
        if not is_valid_maven_coord(g, a, v):
            return False
        assert g is not None and a is not None and v is not None
        key = (g, a, v)
        if key in staged_gavs or (key in gav_cache and gav_cache[key] is None):
            return False
        dst = stage_gav_dir(g, a, v)
        if dst is None:
            return False
        staged_gavs.add(key)
        for p in sorted(dst.glob("*.pom")):
            if not p.is_symlink() and p not in visited_poms:
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
        local_managed: dict[tuple[str, str], set[str]] = {}
        for mg, ma, mv, ms in raw_mgmt:
            rg, ra, rv = res(mg, loc), res(ma, loc), res(mv, loc)
            if rg and ra and rv and "${" not in rg + ra + rv:
                managed.setdefault((rg, ra), set()).add(rv)
                local_managed.setdefault((rg, ra), set()).add(rv)
                if ms == "import" and enqueue_gav(rg, ra, rv):
                    added = True
        for mg, ma, mv, ms in mgmt_templates:
            rg, ra, rv = res(mg, loc), res(ma, loc), res(mv, loc)
            if rg and ra and rv and "${" not in rg + ra + rv:
                local_managed.setdefault((rg, ra), set()).add(rv)
                if ms == "import" and enqueue_gav(rg, ra, rv):
                    added = True
        for dg, da, dv, ds, in_m in raw_deps:
            if in_m and ds != "import":
                continue
            rg, ra, rv = res(dg, loc), res(da, loc), res(dv, loc)
            if rg and ra and "${" not in rg + ra:
                versions = [rv] if (rv and "${" not in rv) else sorted(local_managed.get((rg, ra), set()) | managed.get((rg, ra), set()))
                for vv in versions:
                    if enqueue_gav(rg, ra, vv):
                        added = True
        return added

    while True:
        progressed = False
        while queue:
            pom = queue.pop(0)
            if pom in visited_poms or pom.is_symlink() or not pom.is_file():
                continue
            visited_poms.add(pom)
            own_p, parent, rel_path, raw_mgmt, raw_deps = parse_pom_once(pom)
            for k, val in own_p.items():
                if not k.startswith("project."):
                    shared_props.setdefault(k, val)
            for mg, ma, mv, ms in raw_mgmt:
                if mv and "${" in mv:
                    tpl = (mg, ma, mv, ms)
                    if tpl not in mgmt_templates_seen:
                        mgmt_templates_seen.add(tpl)
                        mgmt_templates.append(tpl)
            specs[pom] = (own_p, raw_mgmt, raw_deps)
            if rel_path and "\x00" not in rel_path and ":" not in rel_path and not rel_path.startswith(("/", "\\")):
                try:
                    pom_res = pom.resolve()
                    if pom_res.is_relative_to(repo_root_res):
                        cand_parent = pom_res.parent / rel_path
                        if not cand_parent.is_symlink():
                            local_parent = cand_parent.resolve()
                            if local_parent.is_relative_to(repo_root_res):
                                if local_parent.is_dir():
                                    local_parent = local_parent / "pom.xml"
                                if (
                                    not local_parent.is_symlink()
                                    and local_parent.resolve().is_relative_to(repo_root_res)
                                    and local_parent.is_file()
                                    and local_parent not in visited_poms
                                ):
                                    queue.append(local_parent)
                except OSError:
                    pass
            if parent and enqueue_gav(*parent):
                progressed = True
            if evaluate_spec(own_p, raw_mgmt, raw_deps):
                progressed = True
        for own_p, raw_mgmt, raw_deps in list(specs.values()):
            if evaluate_spec(own_p, raw_mgmt, raw_deps):
                progressed = True
        if not progressed and not queue:
            break

    core_poms: list[Path] = []
    for group_rel in MAVEN_CORE_GROUPS:
        src = m2_root / group_rel
        if not src.exists() or has_symlink_component(src, m2_root, m2_root_res) or not src.is_dir():
            continue
        try:
            src_res = src.resolve()
        except OSError:
            continue
        if not src_res.is_relative_to(m2_root_res):
            continue
        for root, dirs, files in os.walk(src_res, followlinks=False):
            root_p = Path(root)
            dirs[:] = [d for d in sorted(dirs) if not (root_p / d).is_symlink()]
            for fname in sorted(files):
                if fname.startswith("_remote.repositories") or fname.endswith(".lastUpdated"):
                    continue
                item = root_p / fname
                if item.is_symlink() or not item.is_file():
                    continue
                try:
                    item_res = item.resolve()
                    if not item_res.is_relative_to(m2_root_res):
                        continue
                    rel = item_res.relative_to(m2_root_res)
                except (OSError, ValueError):
                    continue
                dst = m2_stage / rel
                if not dst.resolve(strict=False).is_relative_to(m2_stage_res):
                    continue
                if not dst.exists():
                    dst.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(item_res, dst)
                if dst.suffix == ".pom":
                    core_poms.append(dst)

    # Close parent POMs and <scope>import</scope> BOM descriptors for staged Maven core/plugin POMs
    # so offline Maven 3.9 artifact-descriptor resolution never fails on missing BOM imports (e.g. plexus-19 -> junit-bom).
    bom_visited: set[Path] = set()
    bom_queue: list[Path] = list(core_poms)
    while bom_queue:
        cpom = bom_queue.pop(0)
        if cpom in bom_visited or cpom.is_symlink() or not cpom.is_file():
            continue
        bom_visited.add(cpom)
        own_p, parent, _, raw_mgmt, _ = parse_pom_once(cpom)
        loc = {**shared_props, **own_p}
        targets: list[tuple[str | None, str | None, str | None]] = []
        if parent:
            targets.append(parent)
        for mg, ma, mv, ms in raw_mgmt:
            if ms == "import":
                targets.append((res(mg, loc), res(ma, loc), res(mv, loc)))
        for tg, ta, tv in targets:
            dst_dir = stage_gav_dir(tg, ta, tv)
            if dst_dir is None:
                continue
            for p in sorted(dst_dir.glob("*.pom")):
                if not p.is_symlink() and p not in bom_visited:
                    bom_queue.append(p)


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


def run_isolated_container(docker_bin: list[str], image: str, mode: str) -> tuple[str, str]:
    create_args = [
        "create",
        "--pull=never",
        "--network=none",
        "--read-only",
        "--cap-drop=ALL",
        "--security-opt=no-new-privileges:true",
        f"--memory={MEMORY}",
        f"--memory-swap={MEMORY}",
        "--cpus=2",
        f"--pids-limit={PIDS}",
        "--log-driver=local",
        "--log-opt=max-size=4m",
        "--log-opt=max-file=1",
        "--log-opt=compress=false",
    ]
    if mode == "docker-container":
        create_args.extend([
            "--user", NON_ROOT_USER,
            "--tmpfs", "/work:rw,nosuid,nodev,mode=1777,size=2048m",
            "--tmpfs", "/tmp:rw,nosuid,nodev,mode=1777,size=512m",
        ])
    create_args.append(image)
    container = docker_cmd(docker_bin, *create_args)
    if not re.fullmatch(r"[a-f0-9]{64}", container):
        raise RuntimeError(f"CONTAINER_ID_UNAVAILABLE: {container}")
    try:
        config = json.loads(docker_cmd(docker_bin, "inspect", container))[0]
        host = config["HostConfig"]
        sec_opt = host.get("SecurityOpt") or []
        sec_opt_ok = sec_opt in (["no-new-privileges:true"], ["no-new-privileges"])
        user_ok = (mode != "docker-container") or (config.get("Config", {}).get("User") == NON_ROOT_USER)
        if (
            config["Image"] != image
            or config["Mounts"]
            or host["NetworkMode"] != "none"
            or not host["ReadonlyRootfs"]
            or host["Privileged"]
            or host["CapDrop"] != ["ALL"]
            or not sec_opt_ok
            or not user_ok
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


def write_container_runner_files(ctx_dir: Path, build_tool: str = "maven", ctrl_nonce: str = "") -> None:
    """Writes a hardened networkless container runner (no nested QEMU) when --mode docker-container is selected."""
    if not CONTROL_NONCE_RE.fullmatch(ctrl_nonce):
        raise ValueError("INVALID_CONTROL_NONCE")
    if build_tool in ("node", "python", "go"):
        write_polyglot_container_runner_files(ctx_dir, build_tool, ctrl_nonce)
        return
    ctrl_prefix = f"EVO_CTRL_{ctrl_nonce}:"
    container_dockerfile = """ARG BASE
FROM ${BASE}
USER root
RUN --network=none set -eu; mkdir -p /opt/maven /opt/gradle /opt/census /inputs /work /tmp
COPY tool-dist/ /tmp/tool-dist/
RUN --network=none set -eu; \\
    if [ -f /tmp/tool-dist/maven.zip ]; then \\
      mkdir -p /tmp/maven-dist; \\
      if command -v unzip >/dev/null 2>&1; then unzip -q /tmp/tool-dist/maven.zip -d /tmp/maven-dist; else (cd /tmp/maven-dist && "$JAVA_HOME/bin/jar" xf /tmp/tool-dist/maven.zip); fi; \\
      cp -a /tmp/maven-dist/apache-maven-*/* /opt/maven/; \\
      chmod +x /opt/maven/bin/mvn 2>/dev/null || true; \\
    fi; \\
    if [ -f /tmp/tool-dist/gradle.zip ]; then \\
      mkdir -p /tmp/gradle-dist; \\
      if command -v unzip >/dev/null 2>&1; then unzip -q /tmp/tool-dist/gradle.zip -d /tmp/gradle-dist; else (cd /tmp/gradle-dist && "$JAVA_HOME/bin/jar" xf /tmp/tool-dist/gradle.zip); fi; \\
      cp -a /tmp/gradle-dist/gradle-*/* /opt/gradle/; \\
      chmod +x /opt/gradle/bin/gradle 2>/dev/null || true; \\
    fi; \\
    rm -rf /tmp/tool-dist /tmp/maven-dist /tmp/gradle-dist
COPY ClassDependencyCensus.java /tmp/ClassDependencyCensus.java
RUN --network=none "$JAVA_HOME/bin/javac" -d /opt/census /tmp/ClassDependencyCensus.java && rm -f /tmp/ClassDependencyCensus.java
COPY container-run.sh /opt/run.sh
COPY inputs/ /inputs/
RUN --network=none sed -i 's/\\r$//' /opt/run.sh && chmod -R a+rX /opt /inputs && chmod 0555 /opt/run.sh
ENTRYPOINT ["/bin/sh", "/opt/run.sh"]
"""
    container_script = f"""#!/bin/sh
set -eu
export PATH=/opt/maven/bin:/opt/gradle/bin:"$JAVA_HOME/bin":$PATH LC_ALL=C HOME=/work MAVEN_OPTS="-Xmx1536m" GRADLE_OPTS="-Dorg.gradle.daemon=false -Xmx1536m"
# Non-exported per-run control marker prefix (never exposed to target build/test env).
_EVO_P="{ctrl_prefix}"
emit() {{
  printf '%s%s\\n' "${{_EVO_P}}" "$1"
}}
emit_b64() {{
  TAG="$1"
  FILE="$2"
  emit "${{TAG}}_BEGIN"
  base64 "${{FILE}}" | sed "s/^/${{_EVO_P}}${{TAG}}:/"
  emit "${{TAG}}_END"
}}
cd /inputs
sha256sum -c manifest.sha256 >/dev/null
. /inputs/build-spec.env
emit "EVO_VM_INPUTS $(sha256sum /inputs/manifest.sha256 | cut -d ' ' -f 1)"
emit "EVO_VM_ISOLATION network=loopback-only disks=none"
emit "EVO_BUILD_TOOL ${{EVO_BUILD_TOOL}}"
mkdir -p /work/repo
cp -R /inputs/candidate/. /work/repo/
chmod -R u+rwX /work/repo 2>/dev/null || true
if [ -d /inputs/probes ]; then cp -R /inputs/probes/. /work/repo/; chmod -R u+rwX /work/repo 2>/dev/null || true; fi
if [ "${{EVO_BUILD_TOOL}}" = "gradle" ] && [ -d /inputs/gradle-home ]; then
  mkdir -p /work/gradle-home
  cp -R /inputs/gradle-home/. /work/gradle-home/
  chmod -R u+rwX /work/gradle-home 2>/dev/null || true
fi
cd /work/repo
COMPILE_EXIT=1
TEST_EXIT=1
: > /tmp/compile.log
: > /tmp/test.log
if [ "${{EVO_BUILD_TOOL}}" = "gradle" ]; then
  GRADLE_ARGS="--offline --no-daemon -Dgradle.user.home=/work/gradle-home -Duser.home=/work"
  set +e; gradle ${{GRADLE_ARGS}} testClasses > /tmp/compile.log 2>&1; COMPILE_EXIT=$?; set -e
  emit "EVO_COMPILE_EXIT ${{COMPILE_EXIT}}"
  if [ "${{COMPILE_EXIT}}" -eq 0 ]; then
    emit "EVO_CHECK candidate.compile"
    if [ "${{EVO_SKIP_TESTS:-false}}" = "true" ]; then TEST_EXIT=0; emit "EVO_TESTS_SKIPPED true"
    else set +e; gradle ${{GRADLE_ARGS}} test > /tmp/test.log 2>&1; TEST_EXIT=$?; set -e; fi
  fi
else
  MVN_ARGS="-o -B -ntp -Dmaven.repo.local=/inputs/m2 -Duser.home=/work"
  if [ -n "${{EVO_MVN_PL:-}}" ]; then MVN_ARGS="${{MVN_ARGS}} -pl ${{EVO_MVN_PL}} -am"; fi
  set +e; mvn ${{MVN_ARGS}} test-compile > /tmp/compile.log 2>&1; COMPILE_EXIT=$?; set -e
  emit "EVO_COMPILE_EXIT ${{COMPILE_EXIT}}"
  if [ "${{COMPILE_EXIT}}" -eq 0 ]; then
    emit "EVO_CHECK candidate.compile"
    if [ "${{EVO_SKIP_TESTS:-false}}" = "true" ]; then TEST_EXIT=0; emit "EVO_TESTS_SKIPPED true"
    else set +e; mvn ${{MVN_ARGS}} -DfailIfNoTests=false test > /tmp/test.log 2>&1; TEST_EXIT=$?; set -e; fi
  fi
fi
emit "EVO_TEST_EXIT ${{TEST_EXIT}}"
java -cp /opt/census ClassDependencyCensus /work/repo /tmp/census.json /tmp/census.env /tmp/failures.json /tmp/fail-ids.txt
. /tmp/census.env
emit "EVO_TESTS run=${{TESTS_RUN}} failed=${{TESTS_FAILED}} errors=${{TESTS_ERRORS}}"
emit "EVO_CLASS_COUNT ${{CLASS_COUNT}}"
emit "EVO_CLASS_CENSUS ${{CLASS_CENSUS_SHA256}}"
if [ "${{EVO_SKIP_TESTS:-false}}" != "true" ] && [ "${{COMPILE_EXIT}}" -eq 0 ] && [ "${{TEST_EXIT}}" -eq 0 ] && [ "${{TESTS_FAILED}}" -eq 0 ] && [ "${{TESTS_ERRORS}}" -eq 0 ]; then
  emit "EVO_CHECK candidate.test"
fi
if [ "${{EVO_SKIP_TESTS:-false}}" != "true" ] && [ "${{EVO_BEHAVIORAL_DIFF:-false}}" = "true" ] && [ -d /inputs/baseline ]; then
  mkdir -p /work/base-repo
  cp -R /inputs/baseline/. /work/base-repo/
  chmod -R u+rwX /work/base-repo 2>/dev/null || true
  if [ -d /inputs/probes ]; then cp -R /inputs/probes/. /work/base-repo/; chmod -R u+rwX /work/base-repo 2>/dev/null || true; fi
  cd /work/base-repo
  BASE_TEST_EXIT=1
  : > /tmp/base-test.log
  if [ "${{EVO_BUILD_TOOL}}" = "gradle" ]; then
    set +e; gradle --offline --no-daemon -Dgradle.user.home=/work/gradle-home -Duser.home=/work test > /tmp/base-test.log 2>&1; BASE_TEST_EXIT=$?; set -e
  else
    BASE_MVN_ARGS="-o -B -ntp -Dmaven.repo.local=/inputs/m2 -Duser.home=/work"
    if [ -n "${{EVO_MVN_PL:-}}" ]; then BASE_MVN_ARGS="${{BASE_MVN_ARGS}} -pl ${{EVO_MVN_PL}} -am"; fi
    set +e; mvn ${{BASE_MVN_ARGS}} -DfailIfNoTests=false test > /tmp/base-test.log 2>&1; BASE_TEST_EXIT=$?; set -e
  fi
  java -cp /opt/census ClassDependencyCensus /work/base-repo /tmp/base-census.json /tmp/base-census.env /tmp/base-failures.json /tmp/base-fail-ids.txt || true
  BASE_RUN=0; BASE_FAILED=0; BASE_ERRORS=0
  if [ -f /tmp/base-census.env ]; then
    BASE_RUN=$(grep '^TESTS_RUN=' /tmp/base-census.env | cut -d= -f2 || echo 0)
    BASE_FAILED=$(grep '^TESTS_FAILED=' /tmp/base-census.env | cut -d= -f2 || echo 0)
    BASE_ERRORS=$(grep '^TESTS_ERRORS=' /tmp/base-census.env | cut -d= -f2 || echo 0)
  fi
  BASE_TOTAL_FAIL=$((BASE_FAILED + BASE_ERRORS))
  CAND_TOTAL_FAIL=$((TESTS_FAILED + TESTS_ERRORS))
  CAND_LOG_SRC="/tmp/test.log"
  if [ ! -s /tmp/test.log ] && [ -s /tmp/compile.log ]; then CAND_LOG_SRC="/tmp/compile.log"; fi
  OUT_SHA=$( {{ tail -c 2048 /tmp/base-test.log; echo "---"; tail -c 2048 "${{CAND_LOG_SRC}}"; }} | sha256sum | cut -d' ' -f1 )
  emit "EVO_BEHAVIORAL_DIFF baseExit=${{BASE_TEST_EXIT}} candExit=${{TEST_EXIT}} baseRun=${{BASE_RUN}} candRun=${{TESTS_RUN}} baseFail=${{BASE_TOTAL_FAIL}} candFail=${{CAND_TOTAL_FAIL}} outSha=${{OUT_SHA}}"
  java -cp /opt/census ClassDependencyCensus --behavioral \\
    "${{BASE_TEST_EXIT}}" "${{TEST_EXIT}}" "${{BASE_RUN}}" "${{BASE_TOTAL_FAIL}}" "${{TESTS_RUN}}" "${{CAND_TOTAL_FAIL}}" \\
    "${{OUT_SHA}}" /tmp/base-fail-ids.txt /tmp/fail-ids.txt /tmp/behavioral.json
  emit_b64 "BEHAVIORAL_B64" /tmp/behavioral.json
  IDS_DIFFER=0
  if [ -f /tmp/base-fail-ids.txt ] || [ -f /tmp/fail-ids.txt ]; then
    if ! cmp -s /tmp/base-fail-ids.txt /tmp/fail-ids.txt 2>/dev/null; then IDS_DIFFER=1; fi
  fi
  if [ "${{BASE_TEST_EXIT}}" -ne "${{TEST_EXIT}}" ] || [ "${{BASE_TOTAL_FAIL}}" -ne "${{CAND_TOTAL_FAIL}}" ] || [ "${{TESTS_RUN}}" -lt "${{BASE_RUN}}" ] || [ "${{IDS_DIFFER}}" -ne 0 ]; then
    {{ echo "=== BASELINE ==="; tail -c 2048 /tmp/base-test.log; echo ""; echo "=== CANDIDATE ==="; tail -c 2048 "${{CAND_LOG_SRC}}"; }} | head -c 4096 > /tmp/behavioral-excerpt.txt 2>/dev/null || true
    if [ -s /tmp/behavioral-excerpt.txt ]; then
      emit_b64 "BEHAVIORAL_EXCERPT_B64" /tmp/behavioral-excerpt.txt
    fi
  fi
fi
if [ -s /tmp/failures.json ] && [ "$(cat /tmp/failures.json)" != "[]" ]; then
  emit_b64 "FAILURES_B64" /tmp/failures.json
fi
if [ "${{COMPILE_EXIT}}" -ne 0 ] || [ "${{TEST_EXIT}}" -ne 0 ] || [ "${{TESTS_FAILED}}" -ne 0 ] || [ "${{TESTS_ERRORS}}" -ne 0 ]; then
  {{ tail -c 4096 /tmp/compile.log; echo ""; tail -c 4096 /tmp/test.log; }} > /tmp/logtail.txt 2>/dev/null || true
  if [ -s /tmp/logtail.txt ]; then
    emit_b64 "LOG_TAIL_B64" /tmp/logtail.txt
  fi
fi
emit_b64 "CENSUS_B64" /tmp/census.json
emit "EVO_VM_COMPLETE"
"""
    (ctx_dir / "Dockerfile").write_bytes(normalize_eol(container_dockerfile))
    (ctx_dir / "container-run.sh").write_bytes(normalize_eol(container_script))


GO_CENSUS_SOURCE = r"""package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

type censusDoc struct {
	ClassCount   int                 `json:"classCount"`
	Classes      map[string]string   `json:"classes"`
	Dependencies map[string][]string `json:"dependencies"`
}

type failureEntry struct {
	ClassName  string `json:"className"`
	Kind       string `json:"kind"`
	Message    string `json:"message"`
	MethodName string `json:"methodName"`
}

type behavioralDoc struct {
	BaseExit              int      `json:"baseExit"`
	BaselineFailingTests  []string `json:"baselineFailingTests"`
	BaselineTestsFailed   int      `json:"baselineTestsFailed"`
	BaselineTestsRun      int      `json:"baselineTestsRun"`
	CandExit              int      `json:"candExit"`
	CandidateFailingTests []string `json:"candidateFailingTests"`
	CandidateTestsFailed  int      `json:"candidateTestsFailed"`
	CandidateTestsRun     int      `json:"candidateTestsRun"`
	Executed              bool     `json:"executed"`
	Language              string   `json:"language"`
	OutputExcerpt         string   `json:"outputExcerpt"`
	OutputSha256          string   `json:"outputSha256"`
}

type goTestEvent struct {
	Action  string `json:"Action"`
	Package string `json:"Package"`
	Test    string `json:"Test"`
	Output  string `json:"Output"`
}

func canonicalMarshal(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimSuffix(buf.Bytes(), []byte("\n")), nil
}

func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func parseGoTestLog(path string) (int, int, []string, map[string]string) {
	f, err := os.Open(path)
	if err != nil {
		return 0, 0, []string{}, map[string]string{}
	}
	defer f.Close()
	passed := 0
	failed := 0
	failSet := map[string]struct{}{}
	outputs := map[string]string{}
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if !strings.HasPrefix(line, "{") {
			continue
		}
		var ev goTestEvent
		if err := json.Unmarshal([]byte(line), &ev); err != nil {
			continue
		}
		if ev.Test == "" {
			continue
		}
		id := ev.Test
		if ev.Package != "" {
			id = ev.Package + "#" + ev.Test
		}
		switch ev.Action {
		case "output":
			if len(outputs[id]) < 400 {
				outputs[id] += ev.Output
			}
		case "pass":
			passed++
		case "fail":
			failed++
			failSet[id] = struct{}{}
		}
	}
	failIds := make([]string, 0, len(failSet))
	for k := range failSet {
		failIds = append(failIds, k)
	}
	sort.Strings(failIds)
	return passed, failed, failIds, outputs
}

func tailBytes(path string, limit int) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	if len(data) > limit {
		data = data[len(data)-limit:]
	}
	return string(data)
}

func main() {
	if len(os.Args) < 2 {
		os.Exit(2)
	}
	switch os.Args[1] {
	case "census":
		if len(os.Args) < 9 {
			os.Exit(2)
		}
		repoRoot := os.Args[2]
		testLog := os.Args[3]
		censusOut := os.Args[4]
		envOut := os.Args[5]
		failuresOut := os.Args[6]
		compileExit, _ := strconv.Atoi(os.Args[7])
		testExit, _ := strconv.Atoi(os.Args[8])

		classes := map[string]string{}
		_ = filepath.WalkDir(repoRoot, func(p string, d fs.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			if d.Type()&os.ModeSymlink != 0 {
				if d.IsDir() {
					return filepath.SkipDir
				}
				return nil
			}
			if d.IsDir() {
				name := d.Name()
				if name == ".git" || name == "vendor" || name == "node_modules" {
					return filepath.SkipDir
				}
				return nil
			}
			if strings.HasSuffix(d.Name(), ".go") {
				rel, rerr := filepath.Rel(repoRoot, p)
				if rerr != nil {
					return nil
				}
				raw, rerr := os.ReadFile(p)
				if rerr != nil {
					return nil
				}
				classes[filepath.ToSlash(rel)] = "sha256:" + sha256Hex(raw)
			}
			return nil
		})

		passed, failed, failIds, outputs := parseGoTestLog(testLog)
		run := passed + failed
		if compileExit != 0 {
			run = 0
			failed = 0
			failIds = []string{}
		} else if failed == 0 && testExit != 0 {
			failed = 1
		}

		doc := censusDoc{
			ClassCount:   len(classes),
			Classes:      classes,
			Dependencies: map[string][]string{},
		}
		rawCensus, err := canonicalMarshal(doc)
		if err != nil {
			os.Exit(3)
		}
		if err := os.WriteFile(censusOut, rawCensus, 0o600); err != nil {
			os.Exit(3)
		}
		censusSha := "sha256:" + sha256Hex(rawCensus)
		envText := fmt.Sprintf("TESTS_RUN=%d\nTESTS_FAILED=%d\nTESTS_ERRORS=0\nCLASS_COUNT=%d\nCLASS_CENSUS_SHA256=%s\n",
			run, failed, len(classes), censusSha)
		if err := os.WriteFile(envOut, []byte(envText), 0o600); err != nil {
			os.Exit(3)
		}

		failures := make([]failureEntry, 0, len(failIds))
		for _, id := range failIds {
			if len(failures) >= 30 {
				break
			}
			cls, meth := "go", id
			if idx := strings.LastIndex(id, "#"); idx >= 0 {
				cls, meth = id[:idx], id[idx+1:]
			}
			msg := strings.TrimSpace(outputs[id])
			if len(msg) > 400 {
				msg = msg[:400] + "..."
			}
			failures = append(failures, failureEntry{
				ClassName:  cls,
				Kind:       "FAILURE",
				Message:    msg,
				MethodName: meth,
			})
		}
		if len(failures) == 0 && failed > 0 {
			failures = append(failures, failureEntry{
				ClassName:  "go",
				Kind:       "FAILURE",
				Message:    "go test failed",
				MethodName: "test",
			})
		}
		rawFailures, err := canonicalMarshal(failures)
		if err != nil {
			os.Exit(3)
		}
		if err := os.WriteFile(failuresOut, rawFailures, 0o600); err != nil {
			os.Exit(3)
		}

	case "behavioral":
		if len(os.Args) < 13 {
			os.Exit(2)
		}
		baseTestLog := os.Args[2]
		baseCompileLog := os.Args[3]
		candTestLog := os.Args[4]
		candCompileLog := os.Args[5]
		baseCompileExit, _ := strconv.Atoi(os.Args[6])
		baseTestExit, _ := strconv.Atoi(os.Args[7])
		candTestExit, _ := strconv.Atoi(os.Args[8])
		candRun, _ := strconv.Atoi(os.Args[9])
		candFail, _ := strconv.Atoi(os.Args[10])
		outJson := os.Args[11]
		outEnv := os.Args[12]

		bPass, bFail, bFailIds, _ := parseGoTestLog(baseTestLog)
		bRun := bPass + bFail
		if baseCompileExit != 0 {
			bRun = 0
			bFail = 0
			bFailIds = []string{}
		} else if bFail == 0 && baseTestExit != 0 {
			bFail = 1
		}
		_, _, cFailIds, _ := parseGoTestLog(candTestLog)
		if candFail == 0 {
			cFailIds = []string{}
		}

		baseBody := tailBytes(baseTestLog, 2048)
		if baseBody == "" {
			baseBody = tailBytes(baseCompileLog, 2048)
		}
		candBody := tailBytes(candTestLog, 2048)
		if candBody == "" {
			candBody = tailBytes(candCompileLog, 2048)
		}
		outSha := sha256Hex([]byte(baseBody + "\n---\n" + candBody))
		idsDiffer := len(bFailIds) != len(cFailIds)
		if !idsDiffer {
			for i := range bFailIds {
				if bFailIds[i] != cFailIds[i] {
					idsDiffer = true
					break
				}
			}
		}
		excerpt := ""
		if baseTestExit != candTestExit || bFail != candFail || candRun < bRun || idsDiffer {
			bEx := baseBody
			if len(bEx) > 1024 {
				bEx = bEx[len(bEx)-1024:]
			}
			cEx := candBody
			if len(cEx) > 1024 {
				cEx = cEx[len(cEx)-1024:]
			}
			excerpt = "=== BASELINE ===\n" + bEx + "\n=== CANDIDATE ===\n" + cEx
			if len(excerpt) > 2048 {
				excerpt = excerpt[:2048]
			}
		}
		bdoc := behavioralDoc{
			BaseExit:              baseTestExit,
			BaselineFailingTests:  bFailIds,
			BaselineTestsFailed:   bFail,
			BaselineTestsRun:      bRun,
			CandExit:              candTestExit,
			CandidateFailingTests: cFailIds,
			CandidateTestsFailed:  candFail,
			CandidateTestsRun:     candRun,
			Executed:              true,
			Language:              "go",
			OutputExcerpt:         excerpt,
			OutputSha256:          "sha256:" + outSha,
		}
		rawBehav, err := canonicalMarshal(bdoc)
		if err != nil {
			os.Exit(3)
		}
		if err := os.WriteFile(outJson, rawBehav, 0o600); err != nil {
			os.Exit(3)
		}
		envText := fmt.Sprintf("B_BASE_EXIT=%d\nB_CAND_EXIT=%d\nB_BASE_RUN=%d\nB_CAND_RUN=%d\nB_BASE_FAIL=%d\nB_CAND_FAIL=%d\nB_OUT_SHA=%s\n",
			baseTestExit, candTestExit, bRun, candRun, bFail, candFail, outSha)
		if err := os.WriteFile(outEnv, []byte(envText), 0o600); err != nil {
			os.Exit(3)
		}
	default:
		os.Exit(2)
	}
}
"""


def write_polyglot_container_runner_files(ctx_dir: Path, build_tool: str, ctrl_nonce: str = "") -> None:
    """Writes a hardened networkless polyglot container runner for Node/TypeScript, Python, or Go."""
    if not CONTROL_NONCE_RE.fullmatch(ctrl_nonce):
        raise ValueError("INVALID_CONTROL_NONCE")
    ctrl_prefix = f"EVO_CTRL_{ctrl_nonce}:"
    go_build_layer = ""
    if build_tool == "go":
        (ctx_dir / "go_census.go").write_bytes(normalize_eol(GO_CENSUS_SOURCE))
        go_build_layer = (
            "COPY go_census.go /tmp/go_census.go\n"
            "RUN --network=none set -eu; GOCACHE=/tmp/go-build go build -o /opt/go-census /tmp/go_census.go && rm -rf /tmp/go_census.go /tmp/go-build && chmod 0555 /opt/go-census\n"
        )
    polyglot_dockerfile = f"""ARG BASE
FROM ${{BASE}}
USER root
RUN --network=none set -eu; mkdir -p /opt /inputs /work /tmp
{go_build_layer}COPY container-run.sh /opt/run.sh
COPY inputs/ /inputs/
RUN --network=none sed -i 's/\\r$//' /opt/run.sh && chmod -R a+rX /opt /inputs && chmod 0555 /opt/run.sh
ENTRYPOINT ["/bin/sh", "/opt/run.sh"]
"""
    if build_tool == "node":
        compile_and_test = """
run_node_suite() {
  TARGET_DIR="$1"
  C_LOG="$2"
  T_LOG="$3"
  cd "${TARGET_DIR}"
  export npm_config_offline=true npm_config_audit=false npm_config_fund=false npm_config_update_notifier=false npm_config_cache=/inputs/npm-cache npm_config_logs_dir=/tmp/npm-logs
  if [ -d /inputs/npm-cache/node_modules ] && [ ! -d "${TARGET_DIR}/node_modules" ]; then
    cp -R /inputs/npm-cache/node_modules "${TARGET_DIR}/node_modules"
    chmod -R u+rwX "${TARGET_DIR}/node_modules" 2>/dev/null || true
  fi
  C_EXIT=0
  if [ -f tsconfig.json ]; then
    if [ -x ./node_modules/.bin/tsc ] || command -v tsc >/dev/null 2>&1; then
      set +e; npx --no-install tsc --noEmit > "${C_LOG}" 2>&1; C_EXIT=$?; set -e
    else
      echo "OFFLINE_TOOLCHAIN_MISSING: tsc not installed in offline cache" > "${C_LOG}"
      C_EXIT=127
    fi
  else
    set +e
    find . -maxdepth 4 -type f \\( -name '*.js' -o -name '*.mjs' -o -name '*.cjs' \\) ! -path './node_modules/*' > /tmp/node-js-files.txt
    while IFS= read -r jsf; do
      [ -n "$jsf" ] || continue
      node --check "$jsf" >> "${C_LOG}" 2>&1 || C_EXIT=$?
    done < /tmp/node-js-files.txt
    set -e
  fi
  T_EXIT=1
  if [ "${C_EXIT}" -eq 0 ]; then
    if [ "${EVO_SKIP_TESTS:-false}" = "true" ]; then
      T_EXIT=0
    elif [ -x ./node_modules/.bin/vitest ]; then
      set +e; npx --no-install vitest run > "${T_LOG}" 2>&1; T_EXIT=$?; set -e
    elif [ -x ./node_modules/.bin/jest ]; then
      set +e; npx --no-install jest --ci > "${T_LOG}" 2>&1; T_EXIT=$?; set -e
    elif [ -f package.json ] && grep -q '"test"' package.json; then
      set +e; npm test --offline > "${T_LOG}" 2>&1; T_EXIT=$?; set -e
    else
      set +e; node --test > "${T_LOG}" 2>&1; T_EXIT=$?; set -e
    fi
  fi
  echo "${C_EXIT} ${T_EXIT}"
}
RES=$(run_node_suite /work/repo /tmp/compile.log /tmp/test.log)
COMPILE_EXIT=$(echo "$RES" | awk '{print $1}')
TEST_EXIT=$(echo "$RES" | awk '{print $2}')
export COMPILE_EXIT TEST_EXIT EVO_SKIP_TESTS
emit "EVO_COMPILE_EXIT ${COMPILE_EXIT}"
if [ "${COMPILE_EXIT}" -eq 0 ]; then
  emit "EVO_CHECK candidate.compile"
  if [ "${EVO_SKIP_TESTS:-false}" = "true" ]; then emit "EVO_TESTS_SKIPPED true"; fi
fi
emit "EVO_TEST_EXIT ${TEST_EXIT}"
node -e '
const fs=require("fs"), crypto=require("crypto"), path=require("path");
const root="/work/repo";
const classes={};
function walk(d){
  for(const e of fs.readdirSync(d,{withFileTypes:true})){
    if(e.isSymbolicLink()) continue;
    if(["node_modules",".git","dist","build"].includes(e.name)) continue;
    const p=path.join(d,e.name);
    if(e.isDirectory()) walk(p);
    else if(/\\.(ts|tsx|js|mjs|cjs)$/.test(e.name)){
      const rel=path.relative(root,p).replace(/\\\\/g,"/");
      classes[rel]="sha256:"+crypto.createHash("sha256").update(fs.readFileSync(p)).digest("hex");
    }
  }
}
walk(root);
const tlog=fs.existsSync("/tmp/test.log")?fs.readFileSync("/tmp/test.log","utf8"):"";
const compileFailed=Number(process.env.COMPILE_EXIT||0)!==0;
const testExit=Number(process.env.TEST_EXIT||0);
const passM=tlog.match(/(\\d+)\\s+(?:passed|passing)/i)||tlog.match(/^#\\s*pass\\s+(\\d+)/im)||tlog.match(/\\bpass\\s+(\\d+)/i);
const failM=tlog.match(/(\\d+)\\s+(?:failed|failing)/i)||tlog.match(/^#\\s*fail\\s+(\\d+)/im)||tlog.match(/\\bfail\\s+(\\d+)/i);
const parsedPassed=compileFailed?0:(passM?Number(passM[1]):0);
const parsedFailed=compileFailed?0:(failM?Number(failM[1]):0);
const failed=compileFailed?0:(parsedFailed>0?parsedFailed:(testExit!==0?1:0));
const run=parsedPassed+parsedFailed;
const sorted={}; Object.keys(classes).sort().forEach(k=>sorted[k]=classes[k]);
const census={classCount:Object.keys(sorted).length,classes:sorted,dependencies:{}};
const raw=JSON.stringify(census);
const sha="sha256:"+crypto.createHash("sha256").update(raw).digest("hex");
fs.writeFileSync("/tmp/census.json",raw);
fs.writeFileSync("/tmp/census.env",`TESTS_RUN=${run}\\nTESTS_FAILED=${failed}\\nTESTS_ERRORS=0\\nCLASS_COUNT=${census.classCount}\\nCLASS_CENSUS_SHA256=${sha}\\n`);
fs.writeFileSync("/tmp/failures.json",failed>0?JSON.stringify([{className:"node",methodName:"test",kind:"FAILURE",message:tlog.slice(-600)}]):"[]");
'
if [ "${EVO_SKIP_TESTS:-false}" != "true" ] && [ "${EVO_BEHAVIORAL_DIFF:-false}" = "true" ] && [ -d /inputs/baseline ]; then
  mkdir -p /work/base-repo
  cp -R /inputs/baseline/. /work/base-repo/
  chmod -R u+rwX /work/base-repo 2>/dev/null || true
  if [ -d /inputs/probes ]; then cp -R /inputs/probes/. /work/base-repo/; chmod -R u+rwX /work/base-repo 2>/dev/null || true; fi
  BRES=$(run_node_suite /work/base-repo /tmp/base-compile.log /tmp/base-test.log)
  BASE_COMPILE_EXIT=$(echo "$BRES" | awk '{print $1}')
  BASE_TEST_EXIT=$(echo "$BRES" | awk '{print $2}')
  . /tmp/census.env
  export BASE_COMPILE_EXIT BASE_TEST_EXIT TESTS_RUN TESTS_FAILED TEST_EXIT
  node -e '
const fs=require("fs"), crypto=require("crypto");
function extractNodeFailIds(log){
  const ids=new Set();
  for(const raw of log.split(/\\r?\\n/)){
    const line=raw.trim();
    let m=line.match(/^not ok\\s+\\d+\\s+-\\s+(.+)$/) || line.match(/^[✖✕×●]\\s+(.+)$/) || line.match(/^FAIL\\s+(.+)$/);
    if(m && m[1]){
      const name=m[1].replace(/\\s*\\(\\d+(?:\\.\\d+)?\\s*m?s\\)\\s*$/,"").trim();
      if(name) ids.add(name);
    }
  }
  return [...ids].sort();
}
const blog=fs.existsSync("/tmp/base-test.log")?fs.readFileSync("/tmp/base-test.log","utf8"):"";
const bclog=fs.existsSync("/tmp/base-compile.log")?fs.readFileSync("/tmp/base-compile.log","utf8"):"";
const tlog=fs.existsSync("/tmp/test.log")?fs.readFileSync("/tmp/test.log","utf8"):"";
const clog=fs.existsSync("/tmp/compile.log")?fs.readFileSync("/tmp/compile.log","utf8"):"";
const bce=Number(process.env.BASE_COMPILE_EXIT||0), bte=Number(process.env.BASE_TEST_EXIT||0);
const cte=Number(process.env.TEST_EXIT||0), crun=Number(process.env.TESTS_RUN||0), cfail=Number(process.env.TESTS_FAILED||0);
const bp=blog.match(/(\\d+)\\s+(?:passed|passing)/i)||blog.match(/^#\\s*pass\\s+(\\d+)/im)||blog.match(/\\bpass\\s+(\\d+)/i);
const bf=blog.match(/(\\d+)\\s+(?:failed|failing)/i)||blog.match(/^#\\s*fail\\s+(\\d+)/im)||blog.match(/\\bfail\\s+(\\d+)/i);
const bParsedPass=bce!==0?0:(bp?Number(bp[1]):0);
const bParsedFail=bce!==0?0:(bf?Number(bf[1]):0);
const bfail=bce!==0?0:(bParsedFail>0?bParsedFail:(bte!==0?1:0));
const brun=bParsedPass+bParsedFail;
const bFailIds=bfail>0?extractNodeFailIds(blog):[];
const cFailIds=cfail>0?extractNodeFailIds(tlog):[];
const idsDiffer=JSON.stringify(bFailIds)!==JSON.stringify(cFailIds);
const baseBody=blog||bclog;
const candBody=tlog||clog;
const sha="sha256:"+crypto.createHash("sha256").update(baseBody.slice(-2048)+"\\n---\\n"+candBody.slice(-2048)).digest("hex");
const excerpt=(bte!==cte||bfail!==cfail||crun<brun||idsDiffer)?("=== BASELINE ===\\n"+baseBody.slice(-1024)+"\\n=== CANDIDATE ===\\n"+candBody.slice(-1024)).slice(0,2048):"";
const obj={executed:true,language:"typescript",baseExit:bte,candExit:cte,baselineTestsRun:brun,baselineTestsFailed:bfail,candidateTestsRun:crun,candidateTestsFailed:cfail,baselineFailingTests:bFailIds,candidateFailingTests:cFailIds,outputSha256:sha,outputExcerpt:excerpt};
fs.writeFileSync("/tmp/behavioral.json",JSON.stringify(obj));
fs.writeFileSync("/tmp/behavioral.env",`B_BASE_EXIT=${bte}\\nB_CAND_EXIT=${cte}\\nB_BASE_RUN=${brun}\\nB_CAND_RUN=${crun}\\nB_BASE_FAIL=${bfail}\\nB_CAND_FAIL=${cfail}\\nB_OUT_SHA=${sha.slice(7)}\\n`);
'
  . /tmp/behavioral.env
  emit "EVO_BEHAVIORAL_DIFF baseExit=${B_BASE_EXIT} candExit=${B_CAND_EXIT} baseRun=${B_BASE_RUN} candRun=${B_CAND_RUN} baseFail=${B_BASE_FAIL} candFail=${B_CAND_FAIL} outSha=${B_OUT_SHA}"
  emit_b64 "BEHAVIORAL_B64" /tmp/behavioral.json
fi
"""
    elif build_tool == "python":
        compile_and_test = """
export PYTHONDONTWRITEBYTECODE=1 PIP_NO_INDEX=1
if [ -d /inputs/pip-wheelhouse ]; then
  export PYTHONPATH="/inputs/pip-wheelhouse:${PYTHONPATH:-}"
fi
run_py_suite() {
  TARGET_DIR="$1"
  C_LOG="$2"
  T_LOG="$3"
  cd "${TARGET_DIR}"
  set +e; python3 -m compileall -q . > "${C_LOG}" 2>&1; C_EXIT=$?; set -e
  if [ "${C_EXIT}" -eq 0 ] && command -v mypy >/dev/null 2>&1; then
    set +e; mypy --no-incremental --cache-dir=/tmp/mypy-cache . >> "${C_LOG}" 2>&1; C_EXIT=$?; set -e
  fi
  T_EXIT=1
  if [ "${C_EXIT}" -eq 0 ]; then
    if [ "${EVO_SKIP_TESTS:-false}" = "true" ]; then
      T_EXIT=0
    elif command -v pytest >/dev/null 2>&1; then
      set +e; pytest -q --basetemp=/tmp/pytest-tmp -o cache_dir=/tmp/pytest-cache > "${T_LOG}" 2>&1; T_EXIT=$?; set -e
    else
      set +e; python3 -m unittest discover -q > "${T_LOG}" 2>&1; T_EXIT=$?; set -e
    fi
  fi
  echo "${C_EXIT} ${T_EXIT}"
}
RES=$(run_py_suite /work/repo /tmp/compile.log /tmp/test.log)
COMPILE_EXIT=$(echo "$RES" | awk '{print $1}')
TEST_EXIT=$(echo "$RES" | awk '{print $2}')
export COMPILE_EXIT TEST_EXIT EVO_SKIP_TESTS
emit "EVO_COMPILE_EXIT ${COMPILE_EXIT}"
if [ "${COMPILE_EXIT}" -eq 0 ]; then
  emit "EVO_CHECK candidate.compile"
  if [ "${EVO_SKIP_TESTS:-false}" = "true" ]; then emit "EVO_TESTS_SKIPPED true"; fi
fi
emit "EVO_TEST_EXIT ${TEST_EXIT}"
python3 - <<'PYEOF'
import hashlib, json, os, re
from pathlib import Path
root = Path("/work/repo")
classes = {}
for p in sorted(root.rglob("*.py")):
    if p.is_symlink() or not p.is_file():
        continue
    rel = p.relative_to(root).as_posix()
    classes[rel] = "sha256:" + hashlib.sha256(p.read_bytes()).hexdigest()
tlog = Path("/tmp/test.log").read_text(encoding="utf-8", errors="replace") if Path("/tmp/test.log").is_file() else ""
compile_failed = os.environ.get("COMPILE_EXIT", "0") != "0"
test_exit = int(os.environ.get("TEST_EXIT", "0"))
no_tests = (test_exit == 5) or ("no tests ran" in tlog.lower())
m_pytest_pass = re.search(r"(\\d+)\\s+passed", tlog)
m_unittest_ran = re.search(r"Ran\\s+(\\d+)\\s+test", tlog)
m_fail = re.search(r"(\\d+)\\s+failed", tlog) or re.search(r"failures=(\\d+)", tlog)
m_err = re.search(r"(\\d+)\\s+errors?", tlog) or re.search(r"errors=(\\d+)", tlog)
parsed_fail = 0 if (compile_failed or no_tests) else (int(m_fail.group(1)) if m_fail else 0)
parsed_err = 0 if (compile_failed or no_tests) else (int(m_err.group(1)) if m_err else 0)
failed = parsed_fail
errors = parsed_err
if not compile_failed and not no_tests and test_exit != 0 and failed == 0 and errors == 0:
    failed = 1
if compile_failed or no_tests:
    run = 0
elif m_unittest_ran:
    run = max(int(m_unittest_ran.group(1)), parsed_fail + parsed_err)
elif m_pytest_pass or m_fail or m_err:
    passed = int(m_pytest_pass.group(1)) if m_pytest_pass else 0
    run = passed + parsed_fail + parsed_err
else:
    run = 0
census = {"classCount": len(classes), "classes": classes, "dependencies": {}}
raw = json.dumps(census, sort_keys=True, separators=(",", ":")).encode("utf-8")
sha = "sha256:" + hashlib.sha256(raw).hexdigest()
Path("/tmp/census.json").write_bytes(raw)
Path("/tmp/census.env").write_text(f"TESTS_RUN={run}\\nTESTS_FAILED={failed}\\nTESTS_ERRORS={errors}\\nCLASS_COUNT={len(classes)}\\nCLASS_CENSUS_SHA256={sha}\\n")
failures = [{"className": "pytest", "methodName": "suite", "kind": "FAILURE", "message": tlog[-600:]}] if (failed + errors) > 0 else []
Path("/tmp/failures.json").write_text(json.dumps(failures))
PYEOF
if [ "${EVO_SKIP_TESTS:-false}" != "true" ] && [ "${EVO_BEHAVIORAL_DIFF:-false}" = "true" ] && [ -d /inputs/baseline ]; then
  mkdir -p /work/base-repo
  cp -R /inputs/baseline/. /work/base-repo/
  chmod -R u+rwX /work/base-repo 2>/dev/null || true
  if [ -d /inputs/probes ]; then cp -R /inputs/probes/. /work/base-repo/; chmod -R u+rwX /work/base-repo 2>/dev/null || true; fi
  BRES=$(run_py_suite /work/base-repo /tmp/base-compile.log /tmp/base-test.log)
  BASE_COMPILE_EXIT=$(echo "$BRES" | awk '{print $1}')
  BASE_TEST_EXIT=$(echo "$BRES" | awk '{print $2}')
  . /tmp/census.env
  export BASE_COMPILE_EXIT BASE_TEST_EXIT TESTS_RUN TESTS_FAILED TESTS_ERRORS TEST_EXIT
  python3 - <<'PYBEOF'
import hashlib, json, os, re
from pathlib import Path

def extract_py_fail_ids(log: str) -> list[str]:
    ids: set[str] = set()
    for raw in log.splitlines():
        line = raw.strip()
        m = re.match(r"^(?:FAILED|ERROR)\\s+([^\\s(]\\S*)", line)
        if m:
            ids.add(m.group(1))
            continue
        m = re.match(r"^(?:FAIL|ERROR):\\s+(.+)$", line)
        if m:
            ids.add(m.group(1).strip())
    return sorted(ids)

blog = Path("/tmp/base-test.log").read_text(encoding="utf-8", errors="replace") if Path("/tmp/base-test.log").is_file() else ""
bclog = Path("/tmp/base-compile.log").read_text(encoding="utf-8", errors="replace") if Path("/tmp/base-compile.log").is_file() else ""
tlog = Path("/tmp/test.log").read_text(encoding="utf-8", errors="replace") if Path("/tmp/test.log").is_file() else ""
clog = Path("/tmp/compile.log").read_text(encoding="utf-8", errors="replace") if Path("/tmp/compile.log").is_file() else ""
bce = int(os.environ.get("BASE_COMPILE_EXIT", "0"))
bte = int(os.environ.get("BASE_TEST_EXIT", "0"))
cte = int(os.environ.get("TEST_EXIT", "0"))
crun = int(os.environ.get("TESTS_RUN", "0"))
cfail = int(os.environ.get("TESTS_FAILED", "0")) + int(os.environ.get("TESTS_ERRORS", "0"))
bno_tests = (bte == 5) or ("no tests ran" in blog.lower())
bp = re.search(r"(\\d+)\\s+passed", blog)
bu = re.search(r"Ran\\s+(\\d+)\\s+test", blog)
bf = re.search(r"(\\d+)\\s+failed", blog) or re.search(r"failures=(\\d+)", blog)
be = re.search(r"(\\d+)\\s+errors?", blog) or re.search(r"errors=(\\d+)", blog)
bparsed_fail = 0 if (bce != 0 or bno_tests) else (int(bf.group(1)) if bf else 0)
bparsed_err = 0 if (bce != 0 or bno_tests) else (int(be.group(1)) if be else 0)
bfail_n = bparsed_fail
berr_n = bparsed_err
if bce == 0 and not bno_tests and bte != 0 and bfail_n == 0 and berr_n == 0:
    bfail_n = 1
bfail = bfail_n + berr_n
if bce != 0 or bno_tests:
    brun = 0
elif bu:
    brun = max(int(bu.group(1)), bparsed_fail + bparsed_err)
elif bp or bf or be:
    brun = (int(bp.group(1)) if bp else 0) + bparsed_fail + bparsed_err
else:
    brun = 0
b_fail_ids = extract_py_fail_ids(blog) if bfail > 0 else []
c_fail_ids = extract_py_fail_ids(tlog) if cfail > 0 else []
base_body = blog or bclog
cand_body = tlog or clog
sha = "sha256:" + hashlib.sha256((base_body[-2048:] + "\\n---\\n" + cand_body[-2048:]).encode("utf-8")).hexdigest()
excerpt = (f"=== BASELINE ===\\n{base_body[-1024:]}\\n=== CANDIDATE ===\\n{cand_body[-1024:]}")[:2048] if (bte != cte or bfail != cfail or crun < brun or b_fail_ids != c_fail_ids) else ""
obj = {
    "executed": True, "language": "python", "baseExit": bte, "candExit": cte,
    "baselineTestsRun": brun, "baselineTestsFailed": bfail,
    "candidateTestsRun": crun, "candidateTestsFailed": cfail,
    "baselineFailingTests": b_fail_ids, "candidateFailingTests": c_fail_ids,
    "outputSha256": sha, "outputExcerpt": excerpt,
}
Path("/tmp/behavioral.json").write_text(json.dumps(obj))
Path("/tmp/behavioral.env").write_text(f"B_BASE_EXIT={bte}\\nB_CAND_EXIT={cte}\\nB_BASE_RUN={brun}\\nB_CAND_RUN={crun}\\nB_BASE_FAIL={bfail}\\nB_CAND_FAIL={cfail}\\nB_OUT_SHA={sha[7:]}\\n")
PYBEOF
  . /tmp/behavioral.env
  emit "EVO_BEHAVIORAL_DIFF baseExit=${B_BASE_EXIT} candExit=${B_CAND_EXIT} baseRun=${B_BASE_RUN} candRun=${B_CAND_RUN} baseFail=${B_BASE_FAIL} candFail=${B_CAND_FAIL} outSha=${B_OUT_SHA}"
  emit_b64 "BEHAVIORAL_B64" /tmp/behavioral.json
fi
"""
    else:  # go
        compile_and_test = """
export GOPROXY=off GOSUMDB=off GOFLAGS=-mod=readonly GOCACHE=/tmp/go-build
if [ -d /inputs/go-mod-cache ]; then
  export GOMODCACHE=/inputs/go-mod-cache
else
  mkdir -p /tmp/go-mod-cache
  export GOMODCACHE=/tmp/go-mod-cache
fi
run_go_suite() {
  TARGET_DIR="$1"
  C_LOG="$2"
  T_LOG="$3"
  cd "${TARGET_DIR}"
  set +e; go build ./... > "${C_LOG}" 2>&1; C_EXIT=$?; set -e
  T_EXIT=1
  if [ "${C_EXIT}" -eq 0 ]; then
    if [ "${EVO_SKIP_TESTS:-false}" = "true" ]; then
      T_EXIT=0
    else
      set +e; go test -json ./... > "${T_LOG}" 2>&1; T_EXIT=$?; set -e
    fi
  fi
  echo "${C_EXIT} ${T_EXIT}"
}
RES=$(run_go_suite /work/repo /tmp/compile.log /tmp/test.log)
COMPILE_EXIT=$(echo "$RES" | awk '{print $1}')
TEST_EXIT=$(echo "$RES" | awk '{print $2}')
emit "EVO_COMPILE_EXIT ${COMPILE_EXIT}"
if [ "${COMPILE_EXIT}" -eq 0 ]; then
  emit "EVO_CHECK candidate.compile"
  if [ "${EVO_SKIP_TESTS:-false}" = "true" ]; then emit "EVO_TESTS_SKIPPED true"; fi
fi
emit "EVO_TEST_EXIT ${TEST_EXIT}"
/opt/go-census census /work/repo /tmp/test.log /tmp/census.json /tmp/census.env /tmp/failures.json "${COMPILE_EXIT}" "${TEST_EXIT}"
. /tmp/census.env
if [ "${EVO_SKIP_TESTS:-false}" != "true" ] && [ "${EVO_BEHAVIORAL_DIFF:-false}" = "true" ] && [ -d /inputs/baseline ]; then
  mkdir -p /work/base-repo
  cp -R /inputs/baseline/. /work/base-repo/
  chmod -R u+rwX /work/base-repo 2>/dev/null || true
  if [ -d /inputs/probes ]; then cp -R /inputs/probes/. /work/base-repo/; chmod -R u+rwX /work/base-repo 2>/dev/null || true; fi
  BRES=$(run_go_suite /work/base-repo /tmp/base-compile.log /tmp/base-test.log)
  BASE_COMPILE_EXIT=$(echo "$BRES" | awk '{print $1}')
  BASE_TEST_EXIT=$(echo "$BRES" | awk '{print $2}')
  /opt/go-census behavioral /tmp/base-test.log /tmp/base-compile.log /tmp/test.log /tmp/compile.log \\
    "${BASE_COMPILE_EXIT}" "${BASE_TEST_EXIT}" "${TEST_EXIT}" "${TESTS_RUN}" "${TESTS_FAILED}" \\
    /tmp/behavioral.json /tmp/behavioral.env
  . /tmp/behavioral.env
  emit "EVO_BEHAVIORAL_DIFF baseExit=${B_BASE_EXIT} candExit=${B_CAND_EXIT} baseRun=${B_BASE_RUN} candRun=${B_CAND_RUN} baseFail=${B_BASE_FAIL} candFail=${B_CAND_FAIL} outSha=${B_OUT_SHA}"
  emit_b64 "BEHAVIORAL_B64" /tmp/behavioral.json
fi
"""
    polyglot_script = f"""#!/bin/sh
set -eu
export LC_ALL=C HOME=/work
# Non-exported per-run control marker prefix (never exposed to target build/test env).
_EVO_P="{ctrl_prefix}"
emit() {{
  printf '%s%s\\n' "${{_EVO_P}}" "$1"
}}
emit_b64() {{
  TAG="$1"
  FILE="$2"
  emit "${{TAG}}_BEGIN"
  base64 "${{FILE}}" | sed "s/^/${{_EVO_P}}${{TAG}}:/"
  emit "${{TAG}}_END"
}}
cd /inputs
sha256sum -c manifest.sha256 >/dev/null
. /inputs/build-spec.env
emit "EVO_VM_INPUTS $(sha256sum /inputs/manifest.sha256 | cut -d ' ' -f 1)"
emit "EVO_VM_ISOLATION network=loopback-only disks=none"
emit "EVO_BUILD_TOOL ${{EVO_BUILD_TOOL}}"
mkdir -p /work/repo
cp -R /inputs/candidate/. /work/repo/
chmod -R u+rwX /work/repo 2>/dev/null || true
if [ -d /inputs/probes ]; then cp -R /inputs/probes/. /work/repo/; chmod -R u+rwX /work/repo 2>/dev/null || true; fi
COMPILE_EXIT=1
TEST_EXIT=1
: > /tmp/compile.log
: > /tmp/test.log
{compile_and_test}
. /tmp/census.env
emit "EVO_TESTS run=${{TESTS_RUN}} failed=${{TESTS_FAILED}} errors=${{TESTS_ERRORS}}"
emit "EVO_CLASS_COUNT ${{CLASS_COUNT}}"
emit "EVO_CLASS_CENSUS ${{CLASS_CENSUS_SHA256}}"
if [ "${{EVO_SKIP_TESTS:-false}}" != "true" ] && [ "${{COMPILE_EXIT}}" -eq 0 ] && [ "${{TEST_EXIT}}" -eq 0 ] && [ "${{TESTS_FAILED}}" -eq 0 ] && [ "${{TESTS_ERRORS}}" -eq 0 ]; then
  emit "EVO_CHECK candidate.test"
fi
if [ -s /tmp/failures.json ] && [ "$(cat /tmp/failures.json)" != "[]" ]; then
  emit_b64 "FAILURES_B64" /tmp/failures.json
fi
if [ "${{COMPILE_EXIT}}" -ne 0 ] || [ "${{TEST_EXIT}}" -ne 0 ] || [ "${{TESTS_FAILED}}" -ne 0 ] || [ "${{TESTS_ERRORS}}" -ne 0 ]; then
  {{ tail -c 4096 /tmp/compile.log; echo ""; tail -c 4096 /tmp/test.log; }} > /tmp/logtail.txt 2>/dev/null || true
  if [ -s /tmp/logtail.txt ]; then
    emit_b64 "LOG_TAIL_B64" /tmp/logtail.txt
  fi
fi
emit_b64 "CENSUS_B64" /tmp/census.json
emit "EVO_VM_COMPLETE"
"""
    (ctx_dir / "Dockerfile").write_bytes(normalize_eol(polyglot_dockerfile))
    (ctx_dir / "container-run.sh").write_bytes(normalize_eol(polyglot_script))


def stage_directory_bounded(src_dir: Path, dst_dir: Path, max_bytes: int = 1024 * 1024 * 1024) -> None:
    if not src_dir.is_dir() or src_dir.is_symlink():
        raise FileNotFoundError(f"OFFLINE_TOOLCHAIN_MISSING: cache directory missing or symlinked: {src_dir}")
    src_res = src_dir.resolve()
    copied = 0
    for root, dirs, files in os.walk(src_res, followlinks=False):
        root_p = Path(root)
        dirs[:] = [d for d in sorted(dirs) if not (root_p / d).is_symlink()]
        rel = root_p.relative_to(src_res)
        target_dir = dst_dir / rel
        target_dir.mkdir(parents=True, exist_ok=True)
        for fname in sorted(files):
            sf = root_p / fname
            if sf.is_symlink() or not sf.is_file():
                continue
            try:
                if not sf.resolve().is_relative_to(src_res):
                    continue
            except OSError:
                continue
            copied += sf.stat().st_size
            if copied > max_bytes:
                raise ValueError(f"OFFLINE_CACHE_STAGE_LIMIT_EXCEEDED: {copied} > {max_bytes}")
            shutil.copy2(sf, target_dir / fname)


def stage_gradle_home(gradle_home: Path, dst_home: Path, max_bytes: int = 2048 * 1024 * 1024) -> None:
    if not gradle_home.is_dir() or gradle_home.is_symlink():
        raise FileNotFoundError(f"Gradle home cache missing or symlinked: {gradle_home}")
    home_res = gradle_home.resolve()
    caches = home_res / "caches"
    if caches.is_dir() and not caches.is_symlink():
        dst_caches = dst_home / "caches"
        copied = 0
        for sub in ("modules-2", "jars-9"):
            src_sub = caches / sub
            if not src_sub.is_dir() or src_sub.is_symlink():
                continue
            for root, dirs, files in os.walk(src_sub, followlinks=False):
                root_p = Path(root)
                dirs[:] = [d for d in sorted(dirs) if not (root_p / d).is_symlink() and not d.startswith(GRADLE_SKIP_PREFIXES)]
                rel = root_p.relative_to(src_sub)
                target_dir = dst_caches / sub / rel
                target_dir.mkdir(parents=True, exist_ok=True)
                for fname in sorted(files):
                    if fname in GRADLE_SKIP_FILES or fname.endswith(GRADLE_SKIP_SUFFIXES):
                        continue
                    sf = root_p / fname
                    if sf.is_symlink() or not sf.is_file():
                        continue
                    try:
                        if not sf.resolve().is_relative_to(home_res):
                            continue
                    except OSError:
                        continue
                    copied += sf.stat().st_size
                    if copied > max_bytes:
                        raise ValueError(f"GRADLE_CACHE_STAGE_LIMIT_EXCEEDED: {copied} > {max_bytes}")
                    shutil.copy2(sf, target_dir / fname)


def is_local_windows_named_pipe(endpoint: str) -> bool:
    low = (endpoint or "").strip().lower()
    return low.startswith("npipe:////./pipe/") or low.startswith("npipe://./pipe/")


def resolve_windows_docker_bin(requested_context: str, context_explicit: bool) -> list[str]:
    """Resolves and validates a local Windows named-pipe Docker context without hardcoding only desktop-linux."""
    if os.name != "nt":
        return ["docker", "--context", requested_context] if requested_context else ["docker"]

    candidates: list[str] = []
    if requested_context:
        candidates.append(requested_context)
    if not context_explicit or not requested_context:
        try:
            active_ctx = docker_cmd(["docker"], "context", "show", timeout=10).strip()
            if active_ctx and active_ctx not in candidates:
                candidates.append(active_ctx)
        except Exception:
            pass
    if "default" not in candidates:
        candidates.append("default")

    last_err: Exception | None = None
    for ctx_name in candidates:
        try:
            inspect_raw = docker_cmd(["docker"], "context", "inspect", ctx_name, timeout=15)
            info = json.loads(inspect_raw)[0]
            host = (info.get("Endpoints", {}).get("docker", {}).get("Host") or "").strip()
            if not host and ctx_name == "default":
                host = os.environ.get("DOCKER_HOST", "").strip() or "npipe:////./pipe/docker_engine"
            if not is_local_windows_named_pipe(host):
                raise RuntimeError(f"LOCAL_DOCKER_DAEMON_REQUIRED: {host}")
            return ["docker", "--context", ctx_name] if ctx_name != "default" else ["docker"]
        except RuntimeError:
            raise
        except Exception as exc:
            last_err = exc
            if context_explicit and ctx_name == requested_context:
                raise RuntimeError(f"DOCKER_CONTEXT_UNAVAILABLE: {ctx_name} ({exc})") from exc
    raise RuntimeError(f"LOCAL_DOCKER_DAEMON_REQUIRED: no valid local Windows named-pipe Docker context ({last_err})")


SINGLETON_EVO_PREFIXES = (
    "EVO_VM_INPUTS ",
    "EVO_VM_ISOLATION ",
    "EVO_BUILD_TOOL ",
    "EVO_COMPILE_EXIT ",
    "EVO_TEST_EXIT ",
    "EVO_TESTS ",
    "EVO_CLASS_COUNT ",
    "EVO_CLASS_CENSUS ",
    "EVO_TESTS_SKIPPED ",
    "EVO_BEHAVIORAL_DIFF ",
)
B64_SECTIONS = {
    "CENSUS_B64": "census",
    "FAILURES_B64": "failures",
    "LOG_TAIL_B64": "logtail",
    "BEHAVIORAL_B64": "behavioral",
    "BEHAVIORAL_EXCERPT_B64": "behavioral_excerpt",
}


def parse_authenticated_serial(raw_serial: str, ctrl_nonce: str) -> dict:
    """
    Parses container/VM stdout strictly authenticating every control marker and base64 block with
    the per-run cryptographic prefix EVO_CTRL_<nonce>:. Untrusted unauthenticated target output is
    discarded and duplicate or malformed trusted control markers/sections fail closed.
    """
    if not CONTROL_NONCE_RE.fullmatch(ctrl_nonce):
        raise ValueError("INVALID_CONTROL_NONCE")
    prefix = f"EVO_CTRL_{ctrl_nonce}:"
    auth_lines: list[str] = []
    observed: list[str] = []
    seen_singletons: set[str] = set()
    seen_checks: set[str] = set()
    completed_sections: set[str] = set()
    checks: list[str] = []
    sections_data: dict[str, list[str]] = {v: [] for v in B64_SECTIONS.values()}
    compile_exit = None
    test_exit = None
    tests_run = tests_failed = tests_errors = 0
    census_digest_line = None
    active_tag: str | None = None
    active_section: str | None = None

    for raw_line in raw_serial.replace("\r\n", "\n").splitlines():
        if not raw_line.startswith(prefix):
            continue
        line = raw_line[len(prefix):]
        auth_lines.append(line)

        if active_section is not None:
            assert active_tag is not None
            if line == f"{active_tag}_END":
                completed_sections.add(active_section)
                active_section = None
                active_tag = None
            elif line.startswith(f"{active_tag}:"):
                sections_data[active_section].append(line[len(active_tag) + 1:])
            else:
                raise ValueError(f"MALFORMED_CONTROL_SECTION: unexpected line inside {active_tag}: {line[:80]}")
            continue

        matched_begin = False
        for tag, sec_name in B64_SECTIONS.items():
            if line == f"{tag}_BEGIN":
                if sec_name in completed_sections:
                    raise ValueError(f"DUPLICATE_CONTROL_SECTION: {tag}")
                active_tag = tag
                active_section = sec_name
                matched_begin = True
                break
            if line == f"{tag}_END" or line.startswith(f"{tag}:"):
                raise ValueError(f"MALFORMED_CONTROL_SECTION: stray {tag} marker outside section")
        if matched_begin:
            continue

        if line == "EVO_VM_COMPLETE":
            if "EVO_VM_COMPLETE" in seen_singletons:
                raise ValueError("DUPLICATE_CONTROL_MARKER: EVO_VM_COMPLETE")
            seen_singletons.add("EVO_VM_COMPLETE")
            observed.append(line)
            continue

        for sp in SINGLETON_EVO_PREFIXES:
            if line.startswith(sp):
                if sp in seen_singletons:
                    raise ValueError(f"DUPLICATE_CONTROL_MARKER: {sp.strip()}")
                seen_singletons.add(sp)
                break

        if line.startswith("EVO_"):
            observed.append(line)
        if line.startswith("EVO_COMPILE_EXIT "):
            compile_exit = int(line.split(" ", 1)[1].strip())
        elif line.startswith("EVO_TEST_EXIT "):
            test_exit = int(line.split(" ", 1)[1].strip())
        elif line.startswith("EVO_TESTS "):
            m = re.fullmatch(r"EVO_TESTS run=(\d+) failed=(\d+) errors=(\d+)", line)
            if not m:
                raise ValueError(f"MALFORMED_CONTROL_MARKER: {line}")
            tests_run, tests_failed, tests_errors = int(m.group(1)), int(m.group(2)), int(m.group(3))
        elif line.startswith("EVO_CLASS_CENSUS "):
            census_digest_line = line.split(" ", 1)[1].strip()
        elif line.startswith("EVO_CHECK "):
            chk = line.split(" ", 1)[1].strip()
            if chk in seen_checks:
                raise ValueError(f"DUPLICATE_CONTROL_MARKER: EVO_CHECK {chk}")
            seen_checks.add(chk)
            checks.append(chk)

    if active_section is not None:
        raise ValueError(f"MALFORMED_CONTROL_SECTION: unclosed {active_tag}")

    serial_text = ("\n".join(auth_lines) + "\n") if auth_lines else ""
    return {
        "serial_text": serial_text,
        "observed": observed,
        "checks": checks,
        "compile_exit": compile_exit,
        "test_exit": test_exit,
        "tests_run": tests_run,
        "tests_failed": tests_failed,
        "tests_errors": tests_errors,
        "census_digest_line": census_digest_line,
        "b64_lines": sections_data["census"],
        "failure_b64_lines": sections_data["failures"],
        "logtail_b64_lines": sections_data["logtail"],
        "behavioral_b64_lines": sections_data["behavioral"],
        "behavioral_excerpt_b64_lines": sections_data["behavioral_excerpt"],
    }


def detect_build_tool(repo_dir: Path, requested: str) -> str:
    if requested != "auto":
        return requested
    has_pom = (repo_dir / "pom.xml").is_file()
    has_gradle = (repo_dir / "build.gradle").is_file() or (repo_dir / "build.gradle.kts").is_file() or (repo_dir / "settings.gradle").is_file() or (repo_dir / "settings.gradle.kts").is_file()
    if has_gradle and not has_pom:
        return "gradle"
    if has_pom:
        return "maven"
    if has_gradle:
        return "gradle"
    if (repo_dir / "package.json").is_file() or (repo_dir / "tsconfig.json").is_file():
        return "node"
    if (repo_dir / "pyproject.toml").is_file() or (repo_dir / "pytest.ini").is_file() or (repo_dir / "setup.py").is_file() or (repo_dir / "requirements.txt").is_file():
        return "python"
    if (repo_dir / "go.mod").is_file():
        return "go"
    raise FileNotFoundError(f"OFFLINE_TOOLCHAIN_MISSING: No supported build manifest (Maven/Gradle/Node/Python/Go) found in {repo_dir}")


def node_requires_offline_cache(repo_dir: Path) -> bool:
    if (repo_dir / "tsconfig.json").is_file():
        return True
    pkg = repo_dir / "package.json"
    if pkg.is_file():
        try:
            doc = json.loads(pkg.read_text(encoding="utf-8", errors="replace"))
            if doc.get("dependencies") or doc.get("devDependencies"):
                return True
        except Exception:
            return True
    return False


def effective_behavioral_diff_enabled(behavioral_diff: bool, skip_tests: bool) -> bool:
    """Disables baseline-vs-candidate behavioral diff whenever skip_tests is enabled (I3 epistemic honesty)."""
    return bool(behavioral_diff and not skip_tests)


def build_spec_env(build_tool: str, mvn_pl_list: list[str], skip_tests: bool, behavioral_diff: bool) -> str:
    effective_diff = effective_behavioral_diff_enabled(behavioral_diff, skip_tests)
    return (
        f"EVO_BUILD_TOOL={shlex.quote(build_tool)}\n"
        f"EVO_MVN_PL={shlex.quote(','.join(mvn_pl_list))}\n"
        f"EVO_SKIP_TESTS={shlex.quote('true' if skip_tests else 'false')}\n"
        f"EVO_BEHAVIORAL_DIFF={shlex.quote('true' if effective_diff else 'false')}\n"
    )


def main():
    parser = argparse.ArgumentParser(description="Isolated repair verifier (QEMU-in-Docker or hardened polyglot container)")
    parser.add_argument("--repo", required=True, type=Path, help="Local or materialized baseline repository directory")
    parser.add_argument("--proposal", required=True, type=Path, help="Captured repair proposal or outcome JSON")
    parser.add_argument("--verification", required=True, type=Path, help="Verification result/plan JSON")
    parser.add_argument("--build-tool", default="auto", choices=["auto", "maven", "gradle", "node", "python", "go"], help="Build tool (auto|maven|gradle|node|python|go)")
    parser.add_argument("--mode", default="qemu-docker", choices=["qemu-docker", "docker-container"], help="Isolation backend mode")
    parser.add_argument("--m2", type=Path, help="Local Maven repository (~/.m2/repository)")
    parser.add_argument("--maven-zip", type=Path, help="Pinned apache-maven-*-bin.zip")
    parser.add_argument("--gradle-zip", type=Path, help="Pinned gradle-*-bin.zip")
    parser.add_argument("--gradle-home", type=Path, help="Local Gradle user home (~/.gradle)")
    parser.add_argument("--npm-cache", type=Path, help="Local offline npm cache / pre-installed node_modules bundle")
    parser.add_argument("--pip-wheelhouse", type=Path, help="Local offline Python wheelhouse / site-packages dir")
    parser.add_argument("--go-mod-cache", type=Path, help="Local offline GOMODCACHE directory")
    parser.add_argument("--behavioral-diff", action="store_true", help="Execute tests against both baseline and candidate inside isolated sandbox")
    parser.add_argument("--behavioral-probes", type=Path, help="Optional JSON file of syntax-gated characterization test probes")
    parser.add_argument("--gradle-cache-max-mb", type=int, default=2048, help="Max Gradle cache MiB to stage (default 2048)")
    parser.add_argument("--skip-tests", action="store_true", help="Skip test execution after compile passes")
    parser.add_argument("--out", required=True, type=Path, help="Output capture directory")
    parser.add_argument("--base", default=DEFAULT_BASE_IMAGE, help="Pinned official image digest (eclipse-temurin|node|python|golang@sha256:...)")
    parser.add_argument("--base-cache-tag", default="", help="Optional Docker cache tag for fast layer reuse")
    parser.add_argument("--docker-context", default=None, help="Docker CLI context (empty for default)")
    args = parser.parse_args()

    if not PINNED_BASE_RE.fullmatch(args.base):
        raise ValueError("UNPINNED_BASE_IMAGE")

    repo_src = args.repo.resolve()
    build_tool = detect_build_tool(repo_src, args.build_tool)
    if build_tool == "maven" and (args.m2 is None or args.maven_zip is None):
        raise ValueError("OFFLINE_TOOLCHAIN_MISSING: --m2 and --maven-zip are required for Maven builds")
    if build_tool == "gradle" and (args.gradle_zip is None or args.gradle_home is None):
        raise ValueError("OFFLINE_TOOLCHAIN_MISSING: --gradle-zip and --gradle-home are required for Gradle builds")
    if build_tool in ("node", "python", "go") and args.mode != "docker-container":
        raise ValueError("POLYGLOT_REQUIRES_DOCKER_CONTAINER_MODE: polyglot toolchains require --mode docker-container")
    if build_tool == "node" and args.npm_cache is None:
        nm_path = repo_src / "node_modules"
        has_safe_nm = (not nm_path.is_symlink()) and nm_path.is_dir() and nm_path.resolve().is_relative_to(repo_src)
        if not has_safe_nm and node_requires_offline_cache(repo_src):
            raise ValueError("OFFLINE_TOOLCHAIN_MISSING: --npm-cache or staged node_modules required for offline Node/TypeScript runs")
    if build_tool == "go":
        go_mod_path = repo_src / "go.mod"
        go_mod_text = go_mod_path.read_text(encoding="utf-8", errors="replace") if (not go_mod_path.is_symlink() and go_mod_path.is_file()) else ""
        if "require " in go_mod_text and args.go_mod_cache is None:
            raise ValueError("OFFLINE_TOOLCHAIN_MISSING: --go-mod-cache required for offline Go builds with module dependencies")

    ctx_explicit = args.docker_context is not None
    effective_ctx = args.docker_context if ctx_explicit else ("desktop-linux" if os.name == "nt" else "")
    docker_bin = resolve_windows_docker_bin(effective_ctx, ctx_explicit)

    pinned_base_id: str | None = None
    try:
        pinned_base_id = docker_cmd(docker_bin, "image", "inspect", "--format", "{{.Id}}", args.base, timeout=15).strip()
    except Exception:
        pinned_base_id = None

    script_dir = Path(__file__).resolve().parent
    controls = collect_controls(script_dir)
    proposal_id, plan_id, candidate, affected_modules = resolve_proposal_and_plan(args.proposal, args.verification)

    out_dir = args.out.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    ctx_dir = out_dir / "context"
    safe_rmtree(ctx_dir)
    ctrl_nonce = secrets.token_hex(16)
    iid_file = out_dir / "image-id"
    ephemeral_image_id: str | None = None

    try:
        inputs_dir = ctx_dir / "inputs"
        cand_dir = inputs_dir / "candidate"
        m2_stage = inputs_dir / "m2"
        gradle_stage = inputs_dir / "gradle-home"
        cand_dir.mkdir(parents=True, exist_ok=True)
        m2_stage.mkdir(parents=True, exist_ok=True)
        gradle_stage.mkdir(parents=True, exist_ok=True)

        stage_repo_tree(repo_src, cand_dir)

        effective_behavioral_diff = effective_behavioral_diff_enabled(args.behavioral_diff, args.skip_tests)
        if effective_behavioral_diff:
            base_dir = inputs_dir / "baseline"
            shutil.copytree(cand_dir, base_dir, symlinks=False)

        if not args.skip_tests and args.behavioral_probes is not None and args.behavioral_probes.is_file():
            probes_raw = json.loads(args.behavioral_probes.read_text(encoding="utf-8"))
            probes_dir = inputs_dir / "probes"
            probes_dir.mkdir(parents=True, exist_ok=True)
            for probe in probes_raw if isinstance(probes_raw, list) else []:
                p_rel = probe.get("path", "")
                p_text = probe.get("content", "")
                p_dst = resolve_safe_relative_path(probes_dir, p_rel, "UNSAFE_PROBE_PATH")
                p_dst.parent.mkdir(parents=True, exist_ok=True)
                p_dst.write_bytes(p_text.encode("utf-8"))

        patch_text = candidate["patch"]
        for edit in candidate["edits"]:
            rel_path = edit["path"]
            target_file = resolve_safe_relative_path(cand_dir, rel_path, "UNSAFE_EDIT_PATH")
            before_digest = edit["before"]
            after_digest = edit["after"]
            if before_digest == EMPTY_FILE_SHA256 and after_digest != EMPTY_FILE_SHA256:
                # CREATE
                if target_file.exists():
                    raise ValueError(f"CREATE target already exists in baseline tree: {rel_path}")
                created_text = apply_unified_patch_line_safe("", patch_text, rel_path)
                created_bytes = created_text.encode("utf-8")
                if sha256_bytes(created_bytes) != after_digest:
                    raise ValueError(f"Created file digest mismatch for {rel_path}")
                target_file.parent.mkdir(parents=True, exist_ok=True)
                target_file.write_bytes(created_bytes)
            elif after_digest == EMPTY_FILE_SHA256 and before_digest != EMPTY_FILE_SHA256:
                # DELETE
                if not target_file.is_file():
                    raise FileNotFoundError(f"Deleted file missing from repo copy: {rel_path}")
                if sha256_bytes(target_file.read_bytes()) != before_digest:
                    raise ValueError(f"Baseline file digest mismatch for deleted file {rel_path}")
                target_file.unlink()
            else:
                # MODIFY
                if not target_file.is_file():
                    raise FileNotFoundError(f"Edited file missing from repo copy: {rel_path}")
                raw_bytes = target_file.read_bytes()
                if sha256_bytes(raw_bytes) != before_digest:
                    raise ValueError(f"Baseline file digest mismatch for {rel_path}")
                encoding = "ascii"
                try:
                    raw_bytes.decode("ascii")
                except UnicodeDecodeError:
                    encoding = "utf-8"
                orig_text = raw_bytes.decode(encoding)
                updated_text = apply_unified_patch_line_safe(orig_text, patch_text, rel_path)
                updated_bytes = updated_text.encode("utf-8" if encoding == "utf-8" else "ascii")
                if sha256_bytes(updated_bytes) != after_digest:
                    updated_bytes = updated_text.encode("utf-8")
                    if sha256_bytes(updated_bytes) != after_digest:
                        raise ValueError(f"Patched file digest mismatch for {rel_path}")
                target_file.write_bytes(updated_bytes)

        mvn_pl_list = []
        if build_tool == "maven":
            stage_m2_subset(cand_dir, args.m2, m2_stage)
            cand_dir_res = cand_dir.resolve()
            for m in affected_modules:
                if not m or m == "." or m.startswith("module:"):
                    continue
                if not is_safe_mvn_module_path(m):
                    raise ValueError(f"UNSAFE_MVN_MODULE_PATH: {m}")
                mod_pom = cand_dir / Path(*m.split("/")) / "pom.xml"
                if (
                    not mod_pom.is_symlink()
                    and mod_pom.is_file()
                    and mod_pom.resolve().is_relative_to(cand_dir_res)
                ):
                    mvn_pl_list.append(m)
            if not mvn_pl_list:
                seen_mods = set()
                for edit in candidate["edits"]:
                    rel_parts = Path(*edit["path"].split("/")).parts
                    for end in range(len(rel_parts) - 1, 0, -1):
                        candidate_mod = posixpath.join(*rel_parts[:end])
                        if is_safe_mvn_module_path(candidate_mod):
                            mod_pom = cand_dir / Path(*rel_parts[:end]) / "pom.xml"
                            if not mod_pom.is_symlink() and mod_pom.is_file() and mod_pom.resolve().is_relative_to(cand_dir_res):
                                seen_mods.add(candidate_mod)
                                break
                mvn_pl_list = sorted(seen_mods)
        elif build_tool == "gradle":
            stage_gradle_home(args.gradle_home, gradle_stage, max_bytes=max(256, args.gradle_cache_max_mb) * 1024 * 1024)
        elif build_tool == "node":
            if args.npm_cache is not None:
                stage_directory_bounded(args.npm_cache, inputs_dir / "npm-cache")
            else:
                stage_node_repo_modules(repo_src, inputs_dir / "npm-cache" / "node_modules")
        elif build_tool == "python" and args.pip_wheelhouse is not None:
            stage_directory_bounded(args.pip_wheelhouse, inputs_dir / "pip-wheelhouse")
        elif build_tool == "go" and args.go_mod_cache is not None:
            stage_directory_bounded(args.go_mod_cache, inputs_dir / "go-mod-cache")

        shutil.copy2(script_dir / "ClassDependencyCensus.java", inputs_dir / "ClassDependencyCensus.java")
        shutil.copy2(script_dir / "ClassDependencyCensus.java", ctx_dir / "ClassDependencyCensus.java")
        for mod in mvn_pl_list:
            if not is_safe_mvn_module_path(mod):
                raise ValueError(f"UNSAFE_MVN_MODULE_PATH: {mod}")
        build_spec = build_spec_env(build_tool, mvn_pl_list, args.skip_tests, args.behavioral_diff)
        (inputs_dir / "build-spec.env").write_text(build_spec, encoding="utf-8", newline="\n")

        manifest_entries = []
        for fpath in sorted(inputs_dir.rglob("*")):
            if fpath.is_file() and not fpath.is_symlink():
                logical = posixpath.join(*fpath.relative_to(inputs_dir).parts)
                manifest_entries.append((logical, sha256_file(fpath)[7:]))
        manifest_entries.sort(key=lambda x: x[0])
        manifest_bytes = ("\n".join(f"{hex_d}  {logical}" for logical, hex_d in manifest_entries) + "\n").encode("utf-8")
        (inputs_dir / "manifest.sha256").write_bytes(manifest_bytes)
        (out_dir / "manifest.sha256").write_bytes(manifest_bytes)
        inputs_digest = sha256_bytes(manifest_bytes)

        if args.mode == "docker-container":
            write_container_runner_files(ctx_dir, build_tool, ctrl_nonce)
        else:
            raw_df = (script_dir / "Dockerfile").read_text(encoding="utf-8")
            (ctx_dir / "Dockerfile").write_bytes(normalize_eol(raw_df))
            raw_gi = (script_dir / "guest-init.sh").read_text(encoding="utf-8")
            instantiated_gi = instantiate_guest_init(raw_gi, ctrl_nonce)
            (ctx_dir / "guest-init.sh").write_bytes(normalize_eol(instantiated_gi))

        tool_dist = ctx_dir / "tool-dist"
        tool_dist.mkdir(parents=True, exist_ok=True)
        if args.maven_zip is not None:
            if args.maven_zip.is_symlink() or not args.maven_zip.is_file():
                raise ValueError("OFFLINE_TOOLCHAIN_MISSING: --maven-zip missing or symlinked")
            shutil.copy2(args.maven_zip, tool_dist / "maven.zip")
        if args.gradle_zip is not None:
            if args.gradle_zip.is_symlink() or not args.gradle_zip.is_file():
                raise ValueError("OFFLINE_TOOLCHAIN_MISSING: --gradle-zip missing or symlinked")
            shutil.copy2(args.gradle_zip, tool_dist / "gradle.zip")

        build_cmd = [*docker_bin, "build", "--platform=linux/amd64"]
        if args.mode == "docker-container":
            build_cmd.append("--network=none")
        build_cmd.extend(["--build-arg", f"BASE={args.base}"])
        if args.base_cache_tag:
            build_cmd.extend(["-t", args.base_cache_tag])
        build_cmd.extend(["--iidfile", str(iid_file), str(ctx_dir)])
        subprocess.run(build_cmd, check=True, timeout=900)
        safe_rmtree(tool_dist)
        image_id = iid_file.read_text(encoding="utf-8").strip()
        if not re.fullmatch(r"sha256:[a-f0-9]{64}", image_id):
            raise RuntimeError(f"IMAGE_ID_UNAVAILABLE: {image_id}")
        ephemeral_image_id = image_id

        container_id, raw_serial = run_isolated_container(docker_bin, image_id, args.mode)
    finally:
        try:
            if ephemeral_image_id is None and iid_file.is_file():
                cand_iid = iid_file.read_text(encoding="utf-8", errors="replace").strip()
                if re.fullmatch(r"sha256:[a-f0-9]{64}", cand_iid):
                    ephemeral_image_id = cand_iid
            if ephemeral_image_id and not args.base_cache_tag and ephemeral_image_id != pinned_base_id:
                subprocess.run([*docker_bin, "rmi", "-f", ephemeral_image_id], capture_output=True, timeout=60)
        except Exception:
            pass
        safe_rmtree(ctx_dir)

    parsed_serial = parse_authenticated_serial(raw_serial, ctrl_nonce)
    serial_text = parsed_serial["serial_text"]
    (out_dir / "serial.txt").write_bytes(serial_text.encode("utf-8"))

    observed = parsed_serial["observed"]
    compile_exit = parsed_serial["compile_exit"]
    test_exit = parsed_serial["test_exit"]
    tests_run = parsed_serial["tests_run"]
    tests_failed = parsed_serial["tests_failed"]
    tests_errors = parsed_serial["tests_errors"]
    census_digest_line = parsed_serial["census_digest_line"]
    b64_lines = parsed_serial["b64_lines"]
    failure_b64_lines = parsed_serial["failure_b64_lines"]
    logtail_b64_lines = parsed_serial["logtail_b64_lines"]
    behavioral_b64_lines = parsed_serial["behavioral_b64_lines"]
    behavioral_excerpt_b64_lines = parsed_serial["behavioral_excerpt_b64_lines"]
    checks = parsed_serial["checks"]

    if compile_exit is None or test_exit is None or census_digest_line is None or not b64_lines:
        raise RuntimeError(f"Incomplete VM serial output:\n{serial_text[-2000:]}")

    census_raw = base64.b64decode("".join(b64_lines))
    census_obj = json.loads(census_raw.decode("utf-8"))
    census_canonical_digest = sha256_bytes(canonical_json(census_obj).encode("utf-8"))
    if census_canonical_digest != census_digest_line:
        raise ValueError(f"Census digest mismatch: {census_canonical_digest} vs {census_digest_line}")

    failing_tests = []
    if failure_b64_lines:
        try:
            failing_tests = json.loads(base64.b64decode("".join(failure_b64_lines)).decode("utf-8", errors="replace"))
        except Exception:
            failing_tests = []

    log_tail = ""
    if logtail_b64_lines:
        try:
            log_tail = base64.b64decode("".join(logtail_b64_lines)).decode("utf-8", errors="replace")[:8192]
        except Exception:
            log_tail = ""

    behavioral_diff_obj = None
    if behavioral_b64_lines:
        try:
            behavioral_diff_obj = json.loads(base64.b64decode("".join(behavioral_b64_lines)).decode("utf-8", errors="replace"))
        except Exception:
            behavioral_diff_obj = None
    if behavioral_diff_obj is not None and behavioral_excerpt_b64_lines:
        try:
            decoded_excerpt = base64.b64decode("".join(behavioral_excerpt_b64_lines)).decode("utf-8", errors="replace")[:4096]
            if decoded_excerpt:
                behavioral_diff_obj["outputExcerpt"] = decoded_excerpt
        except Exception:
            pass

    (out_dir / "class-census.json").write_bytes(census_raw)
    normalized_base = args.base[len("docker.io/library/"):] if args.base.startswith("docker.io/library/") else args.base
    capture_doc = {
        "version": VERSION,
        "proposal": proposal_id,
        "plan": plan_id,
        "inputs": inputs_digest,
        "controls": controls,
        "base": normalized_base if PINNED_BASE_RE.fullmatch(normalized_base) else DEFAULT_BASE_IMAGE,
        "image": image_id,
        "container": container_id,
        "buildTool": build_tool,
        "isolationMode": "DOCKER_HARDENED_CONTAINER" if args.mode == "docker-container" else "QEMU_IN_DOCKER",
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
    if args.skip_tests:
        capture_doc["testsSkipped"] = True
    if failing_tests:
        capture_doc["failingTests"] = failing_tests
    if log_tail:
        capture_doc["logTail"] = log_tail
    if behavioral_diff_obj is not None:
        capture_doc["behavioralDiff"] = behavioral_diff_obj

    capture_bytes = canonical_json(capture_doc).encode("utf-8")
    (out_dir / "capture.json").write_bytes(capture_bytes)
    capture_digest = sha256_bytes(capture_bytes)
    print(canonical_json({
        "capture": str(out_dir / "capture.json"),
        "digest": capture_digest,
        "isolationMode": capture_doc["isolationMode"],
        "buildTool": build_tool,
        "compileExit": compile_exit,
        "testExit": test_exit,
        "testsRun": tests_run,
        "testsFailed": tests_failed,
        "classCount": census_obj["classCount"],
    }))


if __name__ == "__main__":
    main()
