"""Check the actual distributable, including IDE error stubs and a passive source/report journey."""
import json
import hashlib
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
        def run(name, *args, timeout=40):
            result = subprocess.run([java, "-Xmx4g", "-jar", str(jar), *args, "-w", workspace, "--json"], cwd=root, capture_output=True, timeout=timeout)
            assert result.returncode in (0, 3), f"{name}: exit {result.returncode}; {result.stderr.decode('utf-8', errors='replace')[:600]}"
            assert b"\x1b" not in result.stdout and not result.stderr, f"{name}: contaminated canonical streams"
            tree = json.loads(result.stdout)
            assert tree["queryVersion"]["version"] == "m8.3-v1", f"{name}: unexpected query contract {tree['queryVersion']}"
            results[name] = result.returncode
            return tree["data"]
        acquired = run("acquire", "acquire", str(fixture), "--resume", "packaged-acquisition")
        assert acquired["semanticCoverage"] == "NOT_EVALUATED" and acquired["acquisition"]["manifest"]["files"] > 0
        inventory = run("inventory", "inventory", "packaged-acquisition", "--limit", "1")
        assert len(inventory["page"]["entries"]) == 1
        readiness = run("readiness", "readiness", "packaged-acquisition", "--section", "roots", "--limit", "1")
        assert readiness["schema"] == "repository-readiness:m8.4-v1" and readiness["total"] > 0
        assert readiness["semanticCoverage"] == "NOT_EVALUATED"
        resumed = run("resume", "acquire", str(fixture), "--resume", "packaged-acquisition")
        assert resumed["acquisition"]["manifest"] == acquired["acquisition"]["manifest"]
        scan = run("scan", "scan", str(fixture), "--offline", "--build", "off")
        assert scan["metrics"]["declaredTypes"] > 0 and scan["snapshotSelected"]
        assert scan["semanticDepth"]["relationships"]["resolved"] > 0
        assert scan["semanticDepth"]["spring"]["status"] == "NOT_EVALUATED"
        assert scan["execution"] == {"mode": "PASSIVE", "isolatedBuildExecuted": False}
        assert scan["polyglot"]["filesByLanguage"]["java"] > 0
        partitions = run("partitions", "graph-pages", "partitions", "--limit", "1")
        assert partitions["scope"] == "RAW_CONTEXT_FACTS" and partitions["semanticAccuracy"] == "NOT_CERTIFIED"
        partition = partitions["page"]["items"][0]
        rows = run("partition-nodes", "graph-pages", "nodes", "--partition", partition["context"], "--limit", "1")
        assert rows["page"]["items"]
        found = run("find", "find", "Controller")
        assert found["items"]
        identity = found["items"][0]["identity"]
        run("show", "show")
        run("dependencies", "deps", identity)
        run("impact", "impact", identity)
        run("policy", "check", str(fixture / "policy.yaml"))
        score = run("assessment", "score")
        assert score["status"] == "WITHHELD" and score["score"] is None
        source = Path(workspace) / "polyglot-input"
        source.mkdir()
        for name, text in {"App.java": "class App {}", "app.py": "print('fixture')", "main.go": "package main\nfunc main(){run()}\nfunc run(){}", "App.cs": "class C { void F(){G();} void G(){} }", "app.js": "app.get('/health', handler); fetch('/health');"}.items():
            (source / name).write_text(text, encoding="utf-8")
        mixed = run("polyglot-scan", "scan", str(source), "--offline", "--build", "off")
        syntax = mixed["polyglot"]["sourceSyntax"]
        assert syntax["provider"] == "source.tree-sitter:m8.4-rpc-local-inheritance-v5"
        assert syntax["parsedDocuments"] == 4 and not syntax["failures"] and syntax["boundCallTargets"] is False
        assert sum(count for category, count in syntax["categories"].items() if category.endswith(":CALL")) == 5
        page = run("syntax-nodes", "graph-pages", "nodes", "--partition", mixed["gapSummary"]["context"], "--limit", "50")
        while True:
            call = next((node for node in page["page"]["items"] if node["kind"] == "CALL_SITE"), None)
            if call is not None:
                break
            cursor = page["page"]["continuation"]
            assert cursor, "No syntax call site in the complete inventory partition"
            page = run("syntax-nodes", "graph-pages", "nodes", "--partition", mixed["gapSummary"]["context"], "--limit", "50", "--cursor", cursor)
        proof = run("syntax-evidence", "graph-pages", "evidence", call["evidence"][0])
        record = json.loads(proof["page"]["evidence"]["canonicalJson"])
        assert record["fields"]["semanticBinding"] == "NOT_ESTABLISHED"
        snapshot = json.loads((Path(workspace) / ".evo" / (mixed["graph"].split(":")[-1] + ".graph.json")).read_text(encoding="utf-8"))["graph"]
        joins = [edge for edge in snapshot["edges"] if edge["kind"] == "polyglot.service-call.candidate"]
        assert len(joins) == 1 and len(joins[0]["target"]["identities"]) == 1
        assert sum(edge["kind"] == "CALLS_SERVICE" for edge in snapshot["edges"]) == 1
        assert not any(edge["kind"] == "polyglot.service-call" for edge in snapshot["edges"])
        assert any(row["family"] == "polyglot.http-uri-match" and json.loads(row["canonicalJson"])["routingEstablished"] is False for row in snapshot["evidence"])
        (source / "cart.proto").write_text('syntax="proto3"; package shop; message Req {} message Reply {} service CartService { rpc GetCart(Req) returns (Reply); }', encoding="utf-8")
        (source / "rpc.go").write_text('package main\nfunc f(){ pb.NewCartServiceClient(conn).GetCart(ctx, req) }\ntype cart struct{}\nfunc(s *cart) GetCart(ctx context.Context, req *Req)(*Reply,error){return nil,nil}\nfunc serve(){svc:=new(cart); pb.RegisterCartServiceServer(server,svc)}', encoding="utf-8")
        (source / "rpc.py").write_text('import demo_pb2_grpc\nclass Base(demo_pb2_grpc.CartServiceServicer):\n    pass\nclass Cart(Base):\n    def GetCart(self, req, ctx):\n        return Reply()\n', encoding="utf-8")
        rpc = run("grpc-scan", "scan", str(source), "--offline", "--build", "off")
        rpc_graph = json.loads((Path(workspace) / ".evo" / (rpc["graph"].split(":")[-1] + ".graph.json")).read_text(encoding="utf-8"))["graph"]
        assert sum(edge["kind"] == "polyglot.grpc-service-call.candidate" for edge in rpc_graph["edges"]) == 1
        assert sum(edge["kind"] == "INVOKES_RPC" for edge in rpc_graph["edges"]) == 1
        rpc_edge = next(edge for edge in rpc_graph["edges"] if edge["kind"] == "INVOKES_RPC")
        assert rpc_edge["target"]["state"] == "CANDIDATES" and len(rpc_edge["target"]["identities"]) == 2
        assert any(row["family"] == "polyglot.grpc-source-match" and json.loads(row["canonicalJson"])["generatedBindingEstablished"] is False for row in rpc_graph["evidence"])
        gap_nodes = [node for node in rpc_graph["nodes"] if node["kind"] == "CAPABILITY_GAP"]
        assert rpc["gapSummary"]["total"] == len({node["identity"] for node in gap_nodes})
        assert rpc["gapSummary"]["contextReferences"] == len(gap_nodes)
        assert rpc["gapSummary"]["inputTotal"] < rpc["gapSummary"]["total"]
        gradle_source = Path(workspace) / "gradle-input"
        (gradle_source / "src/main/java").mkdir(parents=True)
        (gradle_source / "build.gradle").write_text("plugins { id 'java' }\njava { toolchain { languageVersion = JavaLanguageVersion.of(21) } }\n", encoding="utf-8")
        (gradle_source / "src/main/java/GradleClient.java").write_text('''
import java.util.*;
record Pet(String name) {}
class GradleClient {
  int size(String s) {return s.length();}
  List<Pet> normalize(List<Pet> pets) {
    return pets.stream().map(p -> {if(p.name().isBlank())return new Pet("fixed");return p;}).toList();
  }
  Comparator<Pet> ordered() {
    return Comparator.comparing((Pet p) -> p.name(),Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(p -> p.name(),Comparator.nullsLast(Comparator.naturalOrder()));
  }
}
''', encoding="utf-8")
        gradle = run("gradle-scan", "scan", str(gradle_source), "--offline", "--build", "off")
        assert gradle["dependencies"]["gradle"]["trust"] == "PASSIVE_RECOVERY"
        assert gradle["dependencies"]["gradle"]["effectiveGradleContext"] is False
        calls = gradle["semanticDepth"]["relationships"]["byCategory"]["java.calls"]
        assert calls["attempted"] >= 14 and calls["resolved"] == calls["attempted"], f"lambda recovery: {calls}"
        prepared = run("prepare-dependencies", "scan", str(gradle_source), "--prepare-dependencies", "--offline")
        assert prepared["status"] == "PREPARED" and prepared["graphCreated"] is False
        assert prepared["semanticCoverage"] == "NOT_EVALUATED"
        if os.environ.get("EVO_SMOKE_ONLINE_FIXTURE") == "1":
            online = Path(workspace) / "online-input"
            (online / "src/main/java/app").mkdir(parents=True)
            (online / "src/main/java/app/App.java").write_text('package app; class App { void run(org.slf4j.Logger log) { log.info("fixture"); } }', encoding="utf-8")
            (online / "pom.xml").write_text('<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>online</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><dependencies><dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.16</version></dependency></dependencies></project>', encoding="utf-8")
            cache = Path(workspace) / "artifact-cache"
            acquired = run("online-artifacts", "scan", str(online), "--build", "off", "--maven-cache", str(cache), timeout=120)
            assert acquired["semanticDepth"]["relationships"]["byCategory"]["java.calls"]["resolved"] == 1
            artifact = cache / "org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar"
            digest = "sha256:" + hashlib.sha256(artifact.read_bytes()).hexdigest()
            snapshot = json.loads((Path(workspace) / ".evo" / (acquired["graph"].split(":")[-1] + ".graph.json")).read_text(encoding="utf-8"))
            manifests = [json.loads(row["canonicalJson"]) for row in snapshot["graph"]["evidence"] if row["family"] == "analysis.manifest"]
            assert any(entry["logicalName"] == "org.slf4j:slf4j-api:2.0.16@jar" and entry["contentDigest"] == digest for manifest in manifests for entry in manifest["classpath"])
    print(json.dumps({"result": "PASS", "platformClasses": len(classes), "errorStubs": 0, "journeyExits": results}))


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, subprocess.TimeoutExpired, zipfile.BadZipFile) as failure:
        print(json.dumps({"result": "FAIL", "reason": str(failure)}))
        sys.exit(1)
