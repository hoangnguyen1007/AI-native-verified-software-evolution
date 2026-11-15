"""Replay registered M11 passive history using the M9 Windows resource observer."""
import argparse
from hashlib import sha256
from pathlib import Path, PurePosixPath
import shutil

from m9_passive import observe
from m9_protocol import digest, file_sha, git, load, platform_identity, save_new


def require(condition, message):
    if not condition:
        raise ValueError(message)


def capture(objects, revision, destination):
    commit = revision["commit"]
    require(git(objects, "rev-parse", f"{commit}^{{tree}}").decode().strip()
            == revision["tree"], f"Wrong tree: {commit}")
    rows = []
    for record in git(objects, "ls-tree", "-rz", commit).split(b"\0"):
        if not record:
            continue
        descriptor, name = record.split(b"\t", 1)
        mode, kind, oid = descriptor.decode("ascii").split()
        path = PurePosixPath(name.decode("ascii"))
        require(kind == "blob" and mode in ("100644", "100755") and not path.is_absolute()
                and all(p not in ("..", ".git", ".evo") and ":" not in p and "\\" not in p
                        for p in path.parts), f"Unsupported corpus entry: {path}")
        data = git(objects, "cat-file", "blob", oid)
        if destination:
            file = destination.joinpath(*path.parts)
            file.parent.mkdir(parents=True, exist_ok=True)
            with file.open("xb") as out:
                out.write(data)
        rows.append([str(path), mode, len(data), sha256(data).hexdigest()])
    rows.sort()
    require((len(rows), sum(row[2] for row in rows), digest(rows))
            == (revision["entries"], revision["bytes"], revision["inventorySha256"]),
            f"Wrong inventory: {commit}")
    return {row[0]: row[3] for row in rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("java", "jar", "objects", "previous", "output"):
        parser.add_argument(f"--{arg}", required=True, type=lambda p: Path(p).resolve())
    parser.add_argument("--replay", type=lambda p: Path(p).resolve(),
                        help="Reuse all pinned graph publications from an earlier historical capture")
    parser.add_argument("--case", action="append", choices=("module", "configuration", "documentation"),
                        help="Select changed claims for a focused run")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    registry = repo / "docs/tasks/m11/cases.json"
    corpus = load(registry)["naturalHistory"]
    request = load(args.previous / "request.json")
    frozen = args.output
    frozen.mkdir()
    jar = frozen / "candidate.jar"
    shutil.copyfile(args.jar, jar)
    java = args.java
    cache = frozen / "empty-maven-cache"
    cache.mkdir()
    store = frozen / "capture"
    store.mkdir()
    pins = {"b": request["comparison"]["left"], "c": request["comparison"]["right"]}
    if args.replay:
        pinned = load(args.replay / "captured-graphs.json")
        require(set(pinned) == set(corpus["revisions"]) and all(pinned.get(k) == v for k, v in pins.items()),
                "Replay capture does not match the registered base publications")
        pins = pinned
    graph_pages = (args.replay / "capture" if args.replay else args.previous) / ".evo/graph-pages"
    shutil.copytree(graph_pages, store / ".evo/graph-pages")
    cases = args.case or ("pilot", "module", "configuration", "documentation")
    save_new(frozen / "freeze.json", {
        "candidateSha256": file_sha(jar), "javaSha256": file_sha(java),
        "platformModulesSha256": file_sha(java.parent.parent / "lib/modules"),
        "javaReleaseSha256": file_sha(java.parent.parent / "release"),
        "registrySha256": file_sha(registry), "runnerSha256": file_sha(__file__),
        "observerSha256": file_sha(Path(__file__).with_name("m9_passive.py")),
        "source": platform_identity(), "heapGiB": 2, "seconds": 300,
        "reusedGraphRoots": pins.copy(), "passiveFlags": ["--all-files", "--release", "17", "--offline", "--build", "off"],
        "selection": request["comparison"]["selection"], "mode": request["comparison"]["mode"],
        "cases": cases, "replayGraphsFrom": str(args.replay) if args.replay else None,
        "dependencyCache": "EMPTY_OFFLINE", "warmMetric": "fresh JVM reopening verified persisted result",
    })

    def run(label, working_store, command):
        trial = frozen / label
        trial.mkdir()
        argv = [str(java), "-Xmx2g", "-Xlog:gc+heap=debug:file=gc.log", "-jar", str(jar), *map(str, command)]
        save_new(trial / "command.json", argv)
        measurement = observe(argv, trial, working_store, 300, False)
        save_new(trial / "observation.json", measurement)
        print(label, measurement["exit"], measurement["stop"], measurement["wallSeconds"], flush=True)
        if not measurement["stop"] and measurement["exit"] in (0, 3):
            return load(trial / "receipt.json").get("data", {})
        return {}

    inventory = {}
    for key, revision in corpus["revisions"].items():
        source = None if key in pins else frozen / "sources" / key
        inventory[key] = capture(args.objects, revision, source)
    for key in ("module", "documentation", "configuration"):
        pair = corpus[key]
        left, right = pair["left"], pair["right"]
        require(git(args.objects, "rev-parse", corpus["revisions"][right]["commit"] + "^")
                .decode().strip() == corpus["revisions"][left]["commit"], f"Wrong parent: {key}")
        for anchor in pair["anchors"]:
            require((inventory[left].get(anchor["path"]), inventory[right].get(anchor["path"]))
                    == (anchor["leftSha256"], anchor["rightSha256"]), f"Wrong anchor: {anchor}")
    for key in (() if args.replay else ("a", "d", "e")):
        commit = corpus["revisions"][key]["commit"]
        source = frozen / "sources" / key
        data = run(f"scan-{key}", store, ["scan", source, "--all-files", "--release", "17",
                   "--offline", "--build", "off", "--json", "--workspace", store, "--jdk",
                   java.parent.parent, "--maven-cache", cache, "--repo", corpus["repository"],
                   "--revision", commit])
        pins[key] = data.get("graph")
    save_new(frozen / "captured-graphs.json", pins)
    for case in cases:
        pair = corpus["documentation" if case == "pilot" else case]
        left, right = pins[pair["left"]], pins[pair["right"]]
        if not (left and right):
            save_new(frozen / f"{case}-unavailable.json", {"left": left, "right": right, "status": "NOT_RUN"})
            continue
        request["comparison"].update(left=left, right=right)
        request_path = frozen / f"{case}-request.json"
        save_new(request_path, request)
        repeats = 3 if case == "documentation" else 1
        for i in range(1, repeats + 1):
            label = f"{case}-{i}"
            comparison = frozen / (label + "-store")
            shutil.copytree(store / ".evo/graph-pages", comparison / "graph-pages")
            data = run(label + "-cold", comparison,
                       ["evolution", "compare", "--store", comparison, "--request", request_path])
            if data.get("result") and case != "pilot":
                section = "CONTEXTS"
                run(label + "-warm", comparison,
                    ["evolution", "inspect", data["result"], "--store", comparison,
                     "--section", section, "--size", "20"])


if __name__ == "__main__":
    main()
