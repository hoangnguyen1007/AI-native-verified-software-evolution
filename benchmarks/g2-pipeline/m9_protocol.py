"""M9 registration/preflight for the existing G2 entry point. Never executes targets.

The compact registry is versioned; detailed independent census stays outside Git.
Freeze is explicit and refuses overwrite. Preflight only reads and fails closed.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "docs/tasks/m9/registry.json"
PORTABLE_SCHEMA = "m9-evaluation-protocol-v2"
LANGUAGES = {".java": "java", ".kt": "kotlin", ".kts": "kotlin", ".py": "python",
             ".go": "go", ".js": "javascript", ".ts": "typescript", ".tsx": "typescript",
             ".jsx": "javascript", ".vue": "vue", ".proto": "protobuf", ".xml": "xml",
             ".yaml": "yaml", ".yml": "yaml", ".json": "json", ".sql": "sql",
             ".html": "html", ".css": "css", ".rs": "rust", ".cs": "csharp",
             ".c": "c", ".h": "c-header", ".cpp": "cpp", ".rb": "ruby", ".php": "php"}
BUILD_FILES = {"pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
               "go.mod", "package.json", "pyproject.toml", "setup.py", "Cargo.toml", "CMakeLists.txt"}


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(",", ":")).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def file_sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def git(root, *args):
    return subprocess.check_output(["git", "-c", "core.hooksPath=NUL", "-C", str(root), *args],
                                   stderr=subprocess.DEVNULL)


def source_set(path):
    parts = set(path.lower().split("/"))
    if parts & {"vendor", "node_modules", "third_party", "third-party"}:
        return "VENDOR"
    if any("generat" in p for p in parts):
        return "GENERATED"
    if parts & {"test", "tests", "androidtest", "testfixtures", "__tests__"}:
        return "TEST"
    return "MAIN" if "main" in parts else "UNCLASSIFIED"


def census(root):
    """Physical files, no symlink traversal, no analyzer output or build conventions required.

    Owner is the nearest descriptor directory, NOT an effective build context. Nonstandard
    source sets and dynamic generators remain explicit obligations in each context record.
    """
    root = Path(root).resolve()
    paths = []
    for parent, directories, files in os.walk(root, followlinks=False):
        directories.sort()
        if Path(parent) == root and ".git" in directories:
            directories.remove(".git")
        for name in list(directories):
            if (Path(parent) / name).is_symlink():
                files.append(name)
                directories.remove(name)
        paths.extend(Path(parent) / name for name in sorted(files) if name != ".git")
    owners = sorted({p.parent.relative_to(root).as_posix() for p in paths if p.name in BUILD_FILES})
    rows = []
    for path in sorted(paths, key=lambda p: p.relative_to(root).as_posix()):
        relative = path.relative_to(root).as_posix()
        candidates = [o for o in owners if o == "." or relative.startswith(o + "/")]
        owner = max(candidates, key=lambda o: 0 if o == "." else len(o.split("/"))) if candidates else "UNASSIGNED"
        row = {"path": relative, "owner": owner, "language": LANGUAGES.get(path.suffix.lower(), "OTHER"),
               "sourceSet": source_set(relative)}
        if path.is_symlink():
            data = os.readlink(path).encode("utf-8")
            row.update(kind="SYMLINK_NOT_FOLLOWED", bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        else:
            row.update(kind="FILE", bytes=path.stat().st_size, sha256=file_sha(path))
        rows.append(row)
    contexts = []
    for owner in sorted(set(owners) | {r["owner"] for r in rows}):
        owned = [r for r in rows if r["owner"] == owner]
        contexts.append({"owner": owner, "descriptors": [r["path"] for r in owned if Path(r["path"]).name in BUILD_FILES],
                         "languages": dict(sorted(Counter(r["language"] for r in owned).items())),
                         "sourceSets": dict(sorted(Counter(r["sourceSet"] for r in owned).items())),
                         "disposition": "REGISTERED_PASSIVE_NOT_YET_ANALYZED",
                         "effectiveContexts": "UNKNOWN_UNTIL_BUILD_MODEL_RECONCILIATION",
                         "generatorObligation": "RECONCILE_DESCRIPTORS_AND_NATIVE_LINEAGE_NO_IMPLICIT_NONE",
                         "classpath": "NO_NATIVE_CLASSPATH_CAPTURE_REGISTERED"})
    return rows, contexts


def platform_identity():
    # Exclude documentation/registry/test changes from executable identity to avoid self-reference.
    names = git(ROOT, "ls-files", "--cached", "--others", "--exclude-standard", "-z").decode().split("\0")
    names = sorted({n for n in names if n and (("/src/main/" in n and not n.startswith("benchmarks/"))
                    or n.endswith("pom.xml") and not n.startswith("benchmarks/") or n.startswith(".mvn/"))})
    files = [{"path": n, "sha256": file_sha(ROOT / n)} for n in names]
    # Keep the command below Windows' argument limit even for thousands of sources.
    modules = sorted({n.split("/")[0] for n in names if "/src/main/" in n})
    selectors = ["pom.xml", ".mvn", *[x for module in modules for x in (module + "/pom.xml", module + "/src/main")]]
    patch = git(ROOT, "diff", "--binary", "HEAD", "--", *selectors)
    return {"commit": git(ROOT, "rev-parse", "HEAD").decode().strip(), "runtimeSourceSha256": digest(files),
            "runtimeDirtyPatchSha256": hashlib.sha256(patch).hexdigest(), "files": len(files)}


def claim_errors(claims):
    errors, ids = [], set()
    for c in claims:
        if c.get("id") in ids:
            errors.append("duplicate claim: " + c["id"])
        ids.add(c.get("id"))
        for key in ("id", "oracle", "denominator", "cases", "criterion", "owner"):
            if not c.get(key):
                errors.append(f"claim {c.get('id')} missing {key}")
    return errors if claims else ["empty claim denominator"]


class InputLocationError(ValueError):
    """Sanitized configuration failure, never an exemption from byte checks."""


class InputPaths:
    """Bind locations independently of identity. Repo always means this checkout.

    v1 absolute locators remain readable as historical registrations. v2 external
    roots have recorded workstation defaults and explicit local JSON overrides.
    No basename search, implicit tool upgrade, download or missing-file skip.
    """
    def __init__(self, registry, roots_file=None):
        if registry.get("schema") not in ("m9-evaluation-protocol-v1", PORTABLE_SCHEMA):
            raise InputLocationError("Unsupported protocol registration schema")
        self.portable = registry.get("schema") == PORTABLE_SCHEMA
        self.roots = registry.get("pathRoots", {})
        if roots_file and not Path(roots_file).is_file():
            raise InputLocationError("Root bindings file not found; select an existing --roots-file")
        self.overrides = load(roots_file) if roots_file else {}
        if not isinstance(self.overrides, dict) or any(
                key == "repo" or key not in self.roots or not isinstance(value, str) or not value
                for key, value in self.overrides.items()):
            raise InputLocationError("Root overrides must name registered external roots only")

    def __call__(self, reference):
        if not self.portable:
            return Path(reference)
        if not isinstance(reference, str) or ":" not in reference:
            raise InputLocationError("Expected root:relative/path locator")
        key, relative = reference.split(":", 1)
        pure = PurePosixPath(relative)
        if (not relative or "\\" in relative or ":" in relative or pure.is_absolute()
                or PureWindowsPath(relative).drive or ".." in pure.parts):
            raise InputLocationError("Locator must stay within its registered root")
        if key == "repo":
            base = ROOT.resolve()
        else:
            if key not in self.roots:
                raise InputLocationError("Unregistered input root")
            recorded = self.roots[key].get("recordedPath")
            value = self.overrides.get(key, recorded)
            if not isinstance(value, str) or not Path(value).is_absolute():
                raise InputLocationError("External root needs an absolute local binding in --roots-file")
            base = Path(value).resolve()
        result = (base / pure).resolve()
        if not result.is_relative_to(base):
            raise InputLocationError("Resolved input escapes its registered root")
        return result


def matches(pin, paths=None):
    path = paths(pin["path"]) if paths else Path(pin["path"])
    return path.is_file() and file_sha(path) == pin["sha256"]


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def save_new(path, value):
    with Path(path).open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=2, ensure_ascii=True)
        stream.write("\n")


def freeze(args):
    registry = load(REGISTRY)
    destination = Path(args.output).resolve()
    if destination.exists():
        raise ValueError("freeze destination already exists; create a new revision")
    destination.mkdir(parents=True)
    portable = registry.get("schema") == PORTABLE_SCHEMA
    if portable:
        registry["pathRoots"]["corpus"] = {"recordedPath": str(Path(args.corpus_root).resolve())}
        registry["pathRoots"]["evidence"] = {"recordedPath": str(destination)}
    for case in registry["corpus"]:
        root = Path(args.corpus_root) / case["directory"]
        commit = git(root, "rev-parse", "HEAD").decode().strip()
        if not re.fullmatch(r"[0-9a-f]{40}", commit):
            raise ValueError("invalid source commit")
        rows, contexts = census(root)
        inventory = destination / (case["id"] + ".jsonl")
        with inventory.open("x", encoding="utf-8", newline="\n") as stream:
            for row in rows:
                stream.write(canonical(row).decode() + "\n")
        contexts_path = destination / (case["id"] + "-contexts.json")
        save_new(contexts_path, contexts)
        case.update(commit=commit, root="corpus:" + Path(case["directory"]).as_posix() if portable else str(root.resolve()), physicalEntries=len(rows),
                    sourceManifestSha256=digest(rows),
                    dirty=bool(git(root, "status", "--porcelain", "--untracked-files=all").strip()),
                    languages=dict(sorted(Counter(r["language"] for r in rows).items())),
                    sourceSets=dict(sorted(Counter(r["sourceSet"] for r in rows).items())),
                    descriptorOwners=len(contexts),
                    inventory={"path": "evidence:" + inventory.name if portable else str(inventory), "sha256": file_sha(inventory)},
                    contexts={"path": "evidence:" + contexts_path.name if portable else str(contexts_path), "sha256": file_sha(contexts_path)})
        # Public coordinate only; never copy embedded authentication into a registry.
        from urllib.parse import urlsplit
        remote = urlsplit(git(root, "remote", "get-url", "origin").decode().strip())
        if remote.hostname == "github.com":
            case["repository"] = "https://github.com" + remote.path.removesuffix(".git")
        print(json.dumps({"registered": case["id"], "entries": len(rows)}), flush=True)
    jar = destination / "evo-baseline.jar"
    shutil.copyfile(args.jar, jar)
    registry["candidate"] = {**platform_identity(), "jar": {"path": "evidence:" + jar.name if portable else str(jar), "sha256": file_sha(jar)}}
    registry["frozen"] = True
    save_new(destination / "registry.json", registry)
    print(json.dumps({"freeze": str(destination / "registry.json"), "corpus": len(registry["corpus"])}))


def preflight(args):
    if not Path(args.registry).is_file():
        raise InputLocationError("Registry file not found; select an existing --registry")
    registry = load(args.registry)
    paths = InputPaths(registry, getattr(args, "roots_file", None))
    errors = claim_errors(registry["claims"])
    ids = [c["id"] for c in registry["corpus"]]
    if len(set(ids)) != len(ids) or not ids:
        errors.append("empty or duplicate corpus denominator")
    if not registry.get("frozen"):
        errors.append("corpus not frozen")
    if registry["policy"]["passiveThresholdPerMille"] != 950:
        errors.append("approved status policy differs")
    current = platform_identity()
    if registry.get("candidate", {}).get("runtimeSourceSha256") != current["runtimeSourceSha256"]:
        errors.append("candidate runtime source changed")
    if registry["candidate"].get("commit") != current["commit"]:
        errors.append("candidate commit changed; register revision")
    if not matches(registry["candidate"]["jar"], paths):
        errors.append("candidate JAR absent or changed")
    for c in registry["corpus"]:
        if not re.fullmatch(r"[0-9a-f]{40}", c.get("commit", "")):
            errors.append(c["id"] + ": commit not pinned")
        for key in ("inventory", "contexts"):
            if not matches(c[key], paths):
                errors.append(c["id"] + ": " + key + " changed/missing")
        if args.verify_sources:
            try:
                if git(paths(c["root"]), "rev-parse", "HEAD").decode().strip() != c["commit"]:
                    errors.append(c["id"] + ": source commit changed")
                if digest(census(paths(c["root"]))[0]) != c["sourceManifestSha256"]:
                    errors.append(c["id"] + ": source bytes changed")
            except (OSError, subprocess.CalledProcessError):
                errors.append(c["id"] + ": source unavailable")
    for pin in registry.get("pinnedFiles", []):
        if not matches(pin, paths):
            errors.append("registered protocol/labels/version bytes changed: " + pin["path"])
    print(json.dumps({"protocol": registry["schema"], "claims": len(registry["claims"]),
                      "corpus": len(registry["corpus"]), "sourceRehash": args.verify_sources,
                      "errors": errors, "acceptanceRuns": "NOT_RUN"}, ensure_ascii=True))
    return 1 if errors else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    p = sub.add_parser("freeze")
    p.add_argument("--corpus-root", required=True)
    p.add_argument("--jar", required=True)
    p.add_argument("--output", required=True)
    p = sub.add_parser("preflight")
    p.add_argument("--registry", default=str(REGISTRY))
    p.add_argument("--verify-sources", action="store_true")
    p.add_argument("--roots-file", help="JSON map of registered external root names to absolute local directories")
    args = parser.parse_args()
    try:
        return freeze(args) if args.mode == "freeze" else preflight(args)
    except InputLocationError as exc:
        print(json.dumps({"preflight": "FAILED", "reason": "INVALID_INPUT_LOCATION", "detail": str(exc)}))
        return 1
    except (KeyError, ValueError, OSError, subprocess.CalledProcessError) as exc:
        # Do not expose subprocess stderr, target content or ambient environment.
        print(json.dumps({"preflight": "FAILED", "reason": type(exc).__name__}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
