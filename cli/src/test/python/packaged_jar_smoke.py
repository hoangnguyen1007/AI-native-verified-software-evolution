"""Check the actual distributable, including IDE error stubs and a passive source/report journey."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile


def main():
    root = Path(__file__).resolve().parents[4]
    jar = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / "cli/target/evo.jar"
    with zipfile.ZipFile(jar) as archive:
        classes = [n for n in archive.namelist() if n.startswith("com/evolution/") and n.endswith(".class")]
        bad = [n for n in classes if b"Unresolved compilation problem" in archive.read(n)]
        assert not bad, f"IDE error stubs in packaged JAR: {bad[:3]}"
        assert "com/evolution/cli/EvoMain.class" in classes and len(classes) > 500
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe") if os.environ.get("JAVA_HOME") else "java"
    fixture = root / "cli/src/test/resources/fixtures/tiny"
    results = {}
    with tempfile.TemporaryDirectory(prefix="evo-jar-") as workspace:
        def run(name, *args):
            result = subprocess.run([java, "-Xmx4g", "-jar", str(jar), *args, "-w", workspace, "--json"], cwd=root, capture_output=True, timeout=40)
            assert result.returncode in (0, 3), f"{name}: exit {result.returncode}; {result.stderr.decode('utf-8', errors='replace')[:600]}"
            assert b"\x1b" not in result.stdout and not result.stderr, f"{name}: contaminated canonical streams"
            tree = json.loads(result.stdout)
            assert tree["queryVersion"]["version"] == "m7.2-v2"
            results[name] = result.returncode
            return tree["data"]
        scan = run("scan", "scan", str(fixture))
        assert scan["metrics"]["declaredTypes"] > 0 and scan["snapshotSelected"]
        found = run("find", "find", "Controller")
        assert found["items"]
        identity = found["items"][0]["identity"]
        run("show", "show")
        run("dependencies", "deps", identity)
        run("impact", "impact", identity)
        run("policy", "check", str(fixture / "policy.yaml"))
        score = run("assessment", "score")
        assert score["status"] == "WITHHELD" and score["score"] is None
    print(json.dumps({"result": "PASS", "platformClasses": len(classes), "errorStubs": 0, "journeyExits": results}))


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, subprocess.TimeoutExpired, zipfile.BadZipFile) as failure:
        print(json.dumps({"result": "FAIL", "reason": str(failure)}))
        sys.exit(1)
