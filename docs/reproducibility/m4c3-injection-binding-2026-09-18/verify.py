"""Read-only verification of the M4C.3 checkpoint; no target execution or acquisition."""
import argparse
import hashlib
import json
from pathlib import Path


def digest(path, mode):
    data = path.read_bytes()
    if mode == "utf8-lf":
        data = data.decode("utf-8").replace("\r\n", "\n").replace("\r", "\n").encode("utf-8")
    elif mode != "bytes":
        raise ValueError("Unknown hash mode")
    return hashlib.sha256(data).hexdigest()


def workspace_paths(root):
    paths = [root / "pom.xml", root / ".mvn/wrapper/maven-wrapper.properties"]
    for module in ("analyzer", "analyzer-maven", "analyzer-filesystem", "analyzer-javaparser", "backend"):
        paths.append(root / module / "pom.xml")
        paths.extend(p for p in (root / module / "src").rglob("*") if p.is_file())
    return sorted(p.relative_to(root).as_posix() for p in paths)


def check(root, records, actual):
    paths = [record["path"] for record in records]
    if len(set(paths)) != len(paths) or set(paths) != set(actual):
        raise ValueError("Manifest denominator mismatch")
    for record in records:
        relative = Path(record["path"])
        if relative.is_absolute() or ".." in relative.parts or "\\" in record["path"]:
            raise ValueError("Unsafe manifest path")
        target = (root / relative).resolve()
        if not target.is_relative_to(root.resolve()):
            raise ValueError("Manifest path escapes root")
        if digest(target, record["mode"]) != record["sha256"]:
            raise ValueError("Digest mismatch: " + record["path"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", action="store_true")
    args = parser.parse_args()
    package = Path(__file__).resolve().parent
    manifest = json.loads((package / "manifest.json").read_text(encoding="utf-8"))
    if manifest["schema"] != "m4c3-verification-manifest-v1":
        raise ValueError("Unsupported manifest")
    actual = [p.relative_to(package).as_posix() for p in package.rglob("*")
              if p.is_file() and p != package / "manifest.json" and "__pycache__" not in p.parts]
    check(package, manifest["files"], actual)
    execution = json.loads((package / "execution.json").read_text(encoding="utf-8"))
    suites = json.loads((package / "reactor-suites.json").read_text(encoding="utf-8-sig"))
    if len(suites) != 56 or sum(s["Tests"] for s in suites) != 406:
        raise ValueError("Reactor denominator mismatch")
    if execution["reactorExit"] != 0 or any(s[key] for s in suites for key in ("Failures", "Errors", "Skipped")):
        raise ValueError("Reactor did not pass")
    observations = json.loads((package / "oracle-observations.json").read_text(encoding="utf-8"))
    if len(observations) != 23:
        raise ValueError("Oracle denominator mismatch")
    if args.workspace:
        root = package.parents[2]
        inputs = json.loads((package / "workspace-inputs.json").read_text(encoding="utf-8"))
        check(root, inputs, workspace_paths(root))
    print(json.dumps({"status": "PASS", "packageFiles": len(actual), "reactorTests": 406,
                      "reactorSuites": 56, "oracleObservations": 23, "workspaceChecked": args.workspace}, sort_keys=True))


if __name__ == "__main__":
    main()
