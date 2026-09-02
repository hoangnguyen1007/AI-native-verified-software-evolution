"""Run only the trusted local oracle pilot; every attempt creates new evidence files."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import time
import uuid


PACKAGE = Path(__file__).resolve().parent


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def save(path, data):
    path.write_text(json.dumps(data, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


def evaluate(expected, raw_by_group):
    """Compare original source anchors and independent labels; never infer labels from raw output."""
    cases, failures, bindings, extras, groups = [], [], {}, [], []
    for group in expected["groups"]:
        raw = raw_by_group[group["id"]]
        group_failures = []
        if raw["hasErrors"] != group["expectErrors"]:
            group_failures.append("compilation error state differs")
        if raw["options"] != ["--release", "21", "-proc:none", "-encoding", "UTF-8"]:
            group_failures.append("compiler options differ")
        if any(raw[key] for key in ("classpath", "sourcepath", "modulepath")):
            group_failures.append("unexpected compiler resolution path")
        if group["expectErrors"]:
            if not any(d["kind"] == "ERROR" and d["code"].startswith(group["errorCodePrefix"])
                       for d in raw["diagnostics"]):
                group_failures.append("expected diagnostic missing")
            if any(row["oracleAllowed"] for row in raw["calls"]):
                group_failures.append("invalid compilation leaked valid oracle binding")
        known_anchors = set()
        for label in group["labels"]:
            errors = []
            anchor = (label["file"], label["startUtf16"], label["endUtf16"])
            if anchor in known_anchors:
                errors.append("duplicate registered anchor")
            known_anchors.add(anchor)
            matches = [row for row in raw["calls"]
                       if (row["file"], row["startUtf16"], row["endUtf16"]) == anchor]
            if len(matches) != 1:
                errors.append(f"expected exactly one occurrence, observed {len(matches)}")
            else:
                row = matches[0]
                checks = {"sourceText": label["sourceText"], "originalStart": label["start"],
                          "originalEnd": label["end"], "oracleAllowed": label["oracleAllowed"],
                          "hasSourceSpan": True}
                for key, wanted in checks.items():
                    if row[key] != wanted:
                        errors.append(f"{key}: expected {wanted!r}, observed {row[key]!r}")
                if label["oracleAllowed"]:
                    target = row["target"]
                    if target is None:
                        errors.append("expected executable target absent")
                    else:
                        for key, wanted in label["target"].items():
                            if target[key] != wanted:
                                errors.append(f"target.{key}: expected {wanted!r}, observed {target[key]!r}")
                        evidence = target["originEvidence"]
                        if not evidence:
                            errors.append("origin evidence missing")
                        elif target["origin"] == "PROJECT":
                            if evidence["kind"] != "INVENTORIED_SOURCE_DECLARATION" or evidence["file"] != label["file"]:
                                errors.append("project declaration evidence differs")
                            if not 0 <= evidence["declarationStartUtf16"] < evidence["declarationEndUtf16"]:
                                errors.append("project declaration span missing")
                        elif target["origin"] == "JDK":
                            if evidence["kind"] != "COMPILER_PLATFORM_DECLARATION" or not evidence["module"] or not evidence["uri"]:
                                errors.append("platform declaration evidence missing")
                        bindings[label["id"]] = target
            cases.append({"group": group["id"], "id": label["id"],
                          "verdict": "PASS" if not errors else "FAIL", "failures": errors,
                          "semanticTargetAdjudicated": label["oracleAllowed"]})
            failures.extend(f"{label['id']}: {error}" for error in errors)
        for row in raw["calls"]:
            if row["hasSourceSpan"] and (row["file"], row["startUtf16"], row["endUtf16"]) not in known_anchors:
                extras.append({"group": group["id"], "row": row})
                group_failures.append("unregistered explicit call occurrence")
        failures.extend(f"{group['id']}: {error}" for error in group_failures)
        groups.append({"group": group["id"], "registered": len(group["labels"]),
                       "rawCalls": len(raw["calls"]),
                       "implicitOrMissingSpanCalls": sum(not row["hasSourceSpan"] for row in raw["calls"]),
                       "errors": sum(d["kind"] == "ERROR" for d in raw["diagnostics"]),
                       "failures": group_failures})
    relations = []
    for relation in expected["identifierRelations"]:
        def identity(target):
            return (target["owner"], target["name"], target["parameterTypes"], target["originEvidence"])
        left, right = bindings.get(relation["left"]), bindings.get(relation["right"])
        actual = left is not None and right is not None and identity(left) == identity(right)
        passed = left is not None and right is not None and actual == relation["equal"]
        relations.append({**relation, "observedEqual": actual, "verdict": "PASS" if passed else "FAIL"})
        if not passed:
            failures.append(f"identifier equality failed: {relation['left']} / {relation['right']}")
    if not cases:
        failures.append("zero registered cases")
    return {"status": "PASS" if not failures else "FAIL", "caseCount": len(cases),
            "casePassCount": sum(case["verdict"] == "PASS" for case in cases),
            "targetAdjudicatedCount": sum(case["semanticTargetAdjudicated"] for case in cases),
            "blockedCompilationCaseCount": sum(not case["semanticTargetAdjudicated"] for case in cases),
            "identifierRelationCount": len(relations), "groups": groups,
            "cases": cases, "identifierRelations": relations, "unexpectedExplicitCalls": extras,
            "failures": failures, "gate": "DESIGN_PILOT_ONLY_NOT_M2_OR_G2"}


def run(jdk, output_root):
    output_root.mkdir(parents=True, exist_ok=True)
    attempt = output_root / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid.uuid4().hex[:8])
    attempt.mkdir(exist_ok=False)
    commands = []
    sanitized_env = os.environ.copy()
    stripped = []
    for key in ("CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        if key in sanitized_env:
            stripped.append(key)
            del sanitized_env[key]

    def invoke(name, command):
        begin = time.monotonic()
        record = {"name": name, "command": [str(arg) for arg in command], "cwd": str(attempt)}
        commands.append(record)
        save(attempt / "commands.json", commands)
        try:
            completed = subprocess.run(command, cwd=attempt, env=sanitized_env,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
            (attempt / f"{name}.stdout.txt").write_bytes(completed.stdout)
            (attempt / f"{name}.stderr.txt").write_bytes(completed.stderr)
            record.update(exitCode=completed.returncode, elapsedSeconds=time.monotonic() - begin)
            save(attempt / "commands.json", commands)
            if completed.returncode:
                raise RuntimeError(f"{name} failed with exit {completed.returncode}; raw output retained")
            return completed.stdout
        except subprocess.TimeoutExpired as exc:
            (attempt / f"{name}.stdout.txt").write_bytes(exc.stdout or b"")
            (attempt / f"{name}.stderr.txt").write_bytes(exc.stderr or b"")
            record.update(timedOut=True, elapsedSeconds=time.monotonic() - begin)
            save(attempt / "commands.json", commands)
            raise RuntimeError(f"{name} exceeded 60 seconds; partial output retained") from None

    try:
        java = jdk / "bin" / ("java.exe" if os.name == "nt" else "java")
        javac = jdk / "bin" / ("javac.exe" if os.name == "nt" else "javac")
        for binary in (java, javac):
            if not binary.is_file():
                raise ValueError(f"missing JDK executable: {binary}")
        expected_bytes = (PACKAGE / "expected.json").read_bytes()
        expected = json.loads(expected_bytes)
        if not expected["groups"] or any(not group["labels"] for group in expected["groups"]):
            raise ValueError("empty registered corpus/group")
        actual_inventory = {path.relative_to(PACKAGE / "fixtures").as_posix()
                            for path in (PACKAGE / "fixtures").rglob("*.java")}
        if actual_inventory != set(expected["sourceSha256"]):
            raise ValueError("fixture inventory differs from preregistered source inventory")
        for relative, digest in expected["sourceSha256"].items():
            if sha256(PACKAGE / "fixtures" / relative) != digest:
                raise ValueError(f"preregistered source changed: {relative}")
        # Freeze the exact authoring evidence before compiling or running any oracle code.
        (attempt / "preregistration.json").write_bytes(expected_bytes)
        instrumentation = attempt / "instrumentation"
        instrumentation.mkdir()
        for name in ("OraclePilot.java", "run_oracle.py"):
            (instrumentation / name).write_bytes((PACKAGE / name).read_bytes())
        source_root = attempt / "inputs"
        for group in expected["groups"]:
            group_root = source_root / group["id"]
            fixture = PACKAGE / "fixtures" / group["fixture"]
            for path in sorted(fixture.rglob("*.java")):
                destination = group_root / path.relative_to(fixture)
                destination.parent.mkdir(parents=True, exist_ok=True)
                data = path.read_bytes()
                if group.get("transform") == "LF_TO_CRLF":
                    if b"\r" in data:
                        raise ValueError("CRLF transform requires LF-only source")
                    data = data.replace(b"\n", b"\r\n")
                destination.write_bytes(data)
        jdk_files = ["release", "lib/modules", "lib/ct.sym"]
        inventory = {str(path.relative_to(source_root)).replace("\\", "/"): sha256(path)
                     for path in sorted(source_root.rglob("*.java"))}
        manifest = {"schema": "m2-oracle-pilot-run-v1", "jdk": str(jdk),
                    "jdkFiles": {relative: sha256(jdk / relative) for relative in jdk_files},
                    "executables": {str(path.relative_to(jdk)): sha256(path) for path in (java, javac)},
                    "packageFiles": {**{name: sha256(instrumentation / name) for name in ("OraclePilot.java", "run_oracle.py")},
                                     "expected.json": hashlib.sha256(expected_bytes).hexdigest()},
                    "inputSha256": inventory, "python": sys.version,
                    "os": platform.platform(), "strippedEnvironmentKeys": stripped,
                    "classpath": [], "release": 21, "annotationProcessing": False,
                    "sourceInventoryFrozenBeforeCompilerInvocation": True}
        save(attempt / "manifest.json", manifest)
        invoke("java-version", [str(java), "-version"])
        invoke("javac-version", [str(javac), "-version"])
        empty = attempt / "empty-classpath"
        empty.mkdir()
        classes = attempt / "oracle-classes"
        classes.mkdir()
        invoke("compile-oracle", [str(javac), "--release", "21", "-proc:none", "-encoding", "UTF-8",
                                  "-classpath", str(empty), "-sourcepath", str(empty), "-d", str(classes),
                                  str(instrumentation / "OraclePilot.java")])
        raw_by_group = {}
        for group in expected["groups"]:
            group_root = source_root / group["id"]
            files = [str(path.relative_to(group_root)).replace("\\", "/") for path in sorted(group_root.rglob("*.java"))]
            if not files:
                raise ValueError(f"zero source files in {group['id']}")
            data = invoke(group["id"], [str(java), "-cp", str(classes), "OraclePilot", str(group_root), *files])
            raw = json.loads(data)
            raw_by_group[group["id"]] = raw
            save(attempt / f"{group['id']}.raw.json", raw)
        result = evaluate(expected, raw_by_group)
        result["runDirectory"] = str(attempt)
        save(attempt / "summary.json", result)
        print(json.dumps({key: result[key] for key in ("status", "caseCount", "casePassCount", "targetAdjudicatedCount",
                                                       "blockedCompilationCaseCount", "identifierRelationCount", "runDirectory")}))
        if result["failures"]:
            print(json.dumps(result["failures"], ensure_ascii=True), file=sys.stderr)
        return 0 if result["status"] == "PASS" else 1
    except Exception as exc:
        save(attempt / "failure.json", {"errorType": type(exc).__name__, "message": str(exc)})
        print(json.dumps({"status": "ERROR", "runDirectory": str(attempt), "error": str(exc)}), file=sys.stderr)
        return 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True, help="explicit JDK 21 home")
    parser.add_argument("--output-root", type=Path, required=True, help="append-only parent for unique attempt directories")
    args = parser.parse_args()
    return run(args.jdk.resolve(), args.output_root.resolve())


if __name__ == "__main__":
    raise SystemExit(main())
