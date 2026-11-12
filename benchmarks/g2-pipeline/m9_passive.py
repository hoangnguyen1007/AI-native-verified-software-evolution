"""M9.4 passive scale and repeatability cells from the frozen G2 registration."""
import argparse
import ctypes as win
from ctypes import wintypes as wt
from pathlib import Path
import os
import re
import shutil
import subprocess
import sys
from threading import Event, Thread
from time import perf_counter

from m9_protocol import InputPaths, REGISTRY, census, digest, file_sha, git, load, matches, save_new

GIB = 1024 ** 3
REPEAT = ("realworld-go", "petclinic-kotlin", "boutique", "agentscope")
SCALE = ("agentscope", "halo", "fermata", "newpipe", "boutique")
CASES = tuple(dict.fromkeys((*REPEAT, *SCALE)))


class WorkingSet(win.Structure):
    _fields_ = [("size", wt.DWORD), ("faults", wt.DWORD)] + [
        (name, win.c_size_t) for name in
        ("peak", "current", "peakPaged", "paged", "peakNonPaged", "nonPaged", "pagefile", "peakPagefile")
    ]


class JobProcesses(win.Structure):
    _fields_ = [("assigned", wt.DWORD), ("listed", wt.DWORD), ("ids", win.c_size_t * 128)]


class Job:
    """Fence only this authored Java invocation and account for its descendant working sets."""
    def __init__(self, process):
        self.kernel = win.WinDLL("kernel32", use_last_error=True)
        self.memory = win.WinDLL("psapi", use_last_error=True).GetProcessMemoryInfo
        self.memory.argtypes = (wt.HANDLE, win.POINTER(WorkingSet), wt.DWORD)
        signatures = {
            "CreateJobObjectW": (wt.HANDLE, (win.c_void_p, wt.LPCWSTR)),
            "AssignProcessToJobObject": (wt.BOOL, (wt.HANDLE, wt.HANDLE)),
            "QueryInformationJobObject": (wt.BOOL, (wt.HANDLE, win.c_int, win.c_void_p, wt.DWORD, win.c_void_p)),
            "TerminateJobObject": (wt.BOOL, (wt.HANDLE, wt.UINT)),
            "OpenProcess": (wt.HANDLE, (wt.DWORD, wt.BOOL, wt.DWORD)),
            "CloseHandle": (wt.BOOL, (wt.HANDLE,)),
        }
        for name, (restype, argtypes) in signatures.items():
            function = getattr(self.kernel, name)
            function.restype = restype
            function.argtypes = argtypes
        self.handle = self.kernel.CreateJobObjectW(None, None)
        self.root = int(process._handle)
        if not self.handle or not self.kernel.AssignProcessToJobObject(self.handle, self.root):
            error = win.WinError(win.get_last_error())
            if self.handle:
                self.close()
            raise error

    def working_set(self, handle):
        row = WorkingSet()
        row.size = win.sizeof(row)
        return row if self.memory(handle, win.byref(row), row.size) else None

    def sample(self, root_pid):
        rows = JobProcesses()
        if not self.kernel.QueryInformationJobObject(self.handle, 3, win.byref(rows), win.sizeof(rows), None):
            return None
        root = self.working_set(self.root)
        if root is None:
            return None
        total = root.current
        missing = 0
        for pid in rows.ids[:rows.listed]:
            if pid == root_pid:
                continue
            child = self.kernel.OpenProcess(0x410, False, pid)
            data = self.working_set(child) if child else None
            if child:
                self.kernel.CloseHandle(child)
            if data is None:
                missing += 1
            else:
                total += data.current
        return total, root.peak, missing, rows.listed

    def stop(self):
        if not self.kernel.TerminateJobObject(self.handle, 124):
            raise win.WinError(win.get_last_error())

    def close(self):
        self.kernel.CloseHandle(self.handle)


def disk_usage(path):
    pending = [path]
    size = 0
    while pending:
        directory = pending.pop()
        try:
            with os.scandir(directory) as entries:
                for entry in entries:
                    if entry.is_symlink() or os.path.isjunction(entry.path):
                        continue
                    try:
                        if entry.is_dir(follow_symlinks=False):
                            pending.append(entry.path)
                        else:
                            size += entry.stat(follow_symlinks=False).st_size
                    except FileNotFoundError:
                        pass
        except FileNotFoundError:
            pass
    return size


def disk_sampler(workspace, run, finished, samples):
    while not finished.is_set():
        samples.append(disk_usage(workspace) + disk_usage(run))
        finished.wait(2)


def receipt(path, expected_entries):
    try:
        data = load(path)["data"]
    except (ValueError, KeyError):
        return {"receipt": "UNAVAILABLE"}
    counts = data.get("acquisition", {}).get("fullInventory", {})
    return {
        "status": data.get("status"), "evidenceTier": data.get("evidenceTier"),
        "graph": data.get("graph"), "reportIdentity": data.get("reportIdentity"),
        "snapshotSelected": data.get("snapshotSelected"),
        "contexts": len(data.get("contexts", [])), "registeredPhysicalEntries": expected_entries,
        "capturedInventoryEntriesIncludingDirectories": counts.get("entries"),
        "capturedRegularFiles": counts.get("files"),
        "isolatedBuildExecuted": data.get("execution", {}).get("isolatedBuildExecuted"),
        "gapCount": data.get("gapSummary", {}).get("total"),
        "canonicalReceiptFields": digest({k: data.get(k) for k in
            ("graph", "status", "evidenceTier", "reportIdentity", "contexts", "metrics", "gapSummary")}),
    }


def scan(java, jar, source, case, run, workspace, cache, flags):
    scale = case["id"] in SCALE
    seconds, heap = (1800, 8) if scale else (300, 2)
    command = [str(java), f"-Xmx{heap}g", "-Xlog:gc+heap=debug:file=gc.log",
               "-jar", str(jar), "scan", str(source), *flags, "--json", "--debug",
               "--workspace", str(workspace), "--jdk", str(java.parent.parent),
               "--maven-cache", str(cache), "--repo", case["repository"], "--revision", case["commit"]]
    run.mkdir()
    save_new(run / "inputs.json", {"command": command, "profileSeconds": seconds, "heapLimitGiB": heap,
                                  "sourceManifestSha256": case["sourceManifestSha256"]})
    observation = observe(command, run, workspace, seconds, scale)
    debug_counts = None
    with (run / "stderr.log").open(encoding="utf-8", errors="replace") as stream:
        for line in stream:
            match = re.fullmatch(r"\[DEBUG\] graph storage=PARTITIONED_DISK nodes=(\d+) edges=(\d+) evidence=(\d+)\s*", line)
            if match:
                debug_counts = dict(zip(("nodes", "edges", "evidence"), map(int, match.groups())))
    return {"case": case["id"], "graphCounts": debug_counts, **observation,
            **(receipt(run / "receipt.json", case["physicalEntries"]) if not observation["stop"]
               else {"receipt": "RIGHT_CENSORED"})}


def observe(command, run, workspace, seconds, scale):
    tmp = run / "tmp"
    tmp.mkdir()
    out, err = run / "receipt.json", run / "stderr.log"
    env = dict(os.environ, TEMP=str(tmp), TMP=str(tmp))
    for key in ("_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS"):
        env.pop(key, None)
    initial_disk = disk_usage(workspace)
    finished, disk = Event(), []
    monitor = Thread(target=disk_sampler, args=(workspace, run, finished, disk), daemon=True)
    peak_tree = peak_root = samples = missed = max_processes = 0
    longest_interval = 0.0
    reason = None
    with out.open("xb") as stdout, err.open("xb") as stderr:
        started = perf_counter()
        process = subprocess.Popen(command, cwd=run, env=env, stdin=subprocess.DEVNULL,
                                   stdout=stdout, stderr=stderr, creationflags=subprocess.CREATE_NO_WINDOW)
        job = None
        try:
            job = Job(process)
            monitor.start()
            previous = started
            while process.poll() is None:
                now = perf_counter()
                longest_interval = max(longest_interval, now - previous)
                previous = now
                sampled = job.sample(process.pid)
                if sampled is None:
                    missed += 1
                else:
                    total, peak, missing, processes = sampled
                    samples += 1
                    peak_tree = max(peak_tree, total)
                    peak_root = max(peak_root, peak)
                    max_processes = max(max_processes, processes)
                    missed += missing
                if scale and peak_tree >= 12 * GIB:
                    reason = "WORKING_SET_ALLOCATION"
                elif scale and disk and disk[-1] >= 8 * GIB:
                    reason = "SCRATCH_ALLOCATION"
                elif now - started >= seconds:
                    reason = "OBSERVATION_ALLOCATION"
                if reason:
                    job.stop()
                    break
                try:
                    process.wait(timeout=0.075)
                except subprocess.TimeoutExpired:
                    pass
            code = process.wait(timeout=10)
            wall = perf_counter() - started
        finally:
            finished.set()
            if job:
                try:
                    job.stop()  # Also fence any remaining descendants on an interrupted run.
                finally:
                    job.close()
            elif process.poll() is None:
                process.kill()
            if monitor.ident is not None:
                monitor.join()
    final_disk = disk_usage(workspace) + disk_usage(run)
    return {
        "exit": code, "stop": reason, "wallSeconds": round(wall, 3),
        "observedPeakWorkingSetBytes": peak_root, "sampledPeakTreeWorkingSetBytes": peak_tree,
        "workingSetSamples": samples, "maxWorkingSetIntervalMs": round(longest_interval * 1000, 1),
        "missedWorkingSetSamples": missed, "maxSampledProcesses": max_processes,
        "sampledPeakScratchBytes": max([initial_disk, final_disk, *disk]),
        "finalScratchDeltaBytes": final_disk - initial_disk,
        "stdoutBytes": out.stat().st_size, "stdoutSha256": file_sha(out), "stderrSha256": file_sha(err),
    }


def graph_trials(java, jar, classpath, output):
    if not all(Path(entry).is_absolute() and Path(entry).exists() for entry in classpath.split(os.pathsep)):
        raise ValueError("Use explicit compiled graph probe and JUnit dependencies")
    probe = "com.evolution.analysis.filesystem.M9GraphScaleProbe"
    canonical = None
    blocked = None
    valid = True
    for repetition in range(1, 4):
        workspace = output / f"workspace-{repetition}"
        workspace.mkdir()
        for mode in ("cold", "warm"):
            run = output / f"{repetition}-{mode}"
            if blocked:
                observation = {"notRun": blocked}
            else:
                run.mkdir()
                command = [str(java), "-Xmx8g", "-Xlog:gc+heap=debug:file=gc.log",
                           "-cp", str(jar) + os.pathsep + classpath, probe, str(workspace)]
                save_new(run / "inputs.json", {"command": command})
                observation = observe(command, run, workspace, 1800, True)
                if observation["exit"] == 0:
                    root = (run / "receipt.json").read_text(encoding="utf-8").strip()
                    if not re.fullmatch(r"[a-f0-9]{64}\.partitions\.json", root):
                        raise ValueError("Unexpected scale probe root")
                    current = file_sha(workspace / root)
                    canonical = canonical or current
                    observation.update(graph=root.split(".")[0], rootSha256=current, canonicalMatch=current == canonical)
                    valid &= current == canonical
                else:
                    blocked = observation["stop"] or "GRAPH_PROBE_FAILED"
                    valid = False
            observation.update(repetition=repetition, workspaceState=mode)
            save_new(output / f"{repetition}-{mode}.json", observation)
            print(f"33k {repetition}-{mode}: {observation.get('notRun') or observation.get('exit')}", flush=True)
    return 0 if valid else 1


def execute(args):
    if sys.platform != "win32":
        raise ValueError("The frozen passive profile requires Windows")
    baseline = load(REGISTRY)
    registry = load(args.registry)
    paths = InputPaths(registry, args.roots_file)
    historical = InputPaths(baseline, args.roots_file)
    selected = baseline if args.role == "baseline" else registry
    selected_paths = historical if args.role == "baseline" else paths
    artifact = selected["candidate"]["jar"]
    if not matches(artifact, selected_paths):
        raise ValueError("Registered packaged analyzer bytes differ")
    for pin in registry["pinnedFiles"]:
        if pin["path"].startswith("jdk:") and not matches(pin, paths):
            raise ValueError("Registered JDK bytes differ")
    flags = baseline["selection"]["passive"]
    if flags != ["--offline", "--build=off", "--all-files", "--include-tests", "--release=21"]:
        raise ValueError("The frozen passive selection differs")
    cases = {case["id"]: case for case in baseline["corpus"]}
    wanted = () if args.graph_classpath else args.case or CASES
    output = Path(args.output).resolve()
    if not Path(args.output).is_absolute() or output.is_relative_to(REGISTRY.parents[3].resolve()):
        raise ValueError("Select a new absolute external output directory")
    output.mkdir(parents=True)
    cache = output / "empty-artifact-cache"
    cache.mkdir()
    save_new(output / "registration.json", {
        "protocolSha256": file_sha(REGISTRY), "candidateRegistrySha256": file_sha(args.registry),
        "driverSha256": file_sha(__file__), "cases": wanted, "role": args.role, "jar": artifact,
        "jdk": str(paths("jdk:.")), "warm": "new JVM reusing its cold trial's durable workspace",
        "osPageCache": "UNCONTROLLED", "graphClasspath": args.graph_classpath,
    })
    java, jar = paths("jdk:bin/java.exe"), selected_paths(artifact["path"])
    if args.graph_classpath:
        return graph_trials(java, jar, args.graph_classpath, output)
    valid = True
    for identifier in wanted:
        case = cases[identifier]
        source = historical(case["root"])
        try:
            ready = git(source, "rev-parse", "HEAD").decode().strip() == case["commit"]
            ready = ready and digest(census(source)[0]) == case["sourceManifestSha256"]
        except (OSError, subprocess.CalledProcessError):
            ready = False
        repetitions = 3 if args.role == "candidate" and identifier in REPEAT else 1
        modes = ("cold", "warm") if args.role == "candidate" and identifier in REPEAT else ("cold",)
        case_dir = output / identifier
        case_dir.mkdir()
        blocked = None if ready else "SOURCE_IDENTITY_MISMATCH"
        for repeat in range(1, repetitions + 1):
            workspace = case_dir / f"workspace-{repeat}"
            for mode in modes:
                run = case_dir / f"{repeat}-{mode}"
                available = shutil.disk_usage(output).free
                if identifier in SCALE and available < 8 * GIB:
                    blocked = "HOST_SCRATCH_UNAVAILABLE"
                if blocked:
                    result = {"case": identifier, "notRun": blocked}
                else:
                    result = scan(java, jar, source, case, run, workspace, cache, flags)
                    if result["stop"]:
                        blocked = "PREVIOUS_ATTEMPT_CENSORED"
                    elif result["exit"] not in (0, 3) or not result.get("snapshotSelected"):
                        blocked = "PREVIOUS_ATTEMPT_DID_NOT_PUBLISH"
                result.update(repetition=repeat, workspaceState=mode)
                save_new(case_dir / f"{repeat}-{mode}.json", result)
                valid &= not (blocked or result.get("isolatedBuildExecuted") is True)
                print(f"{identifier} {repeat}-{mode}: {result.get('notRun') or result.get('stop') or result.get('status')}"
                      f" ({result.get('wallSeconds', 'n/a')} s)", flush=True)
    return 0 if valid else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", required=True)
    parser.add_argument("--roots-file")
    parser.add_argument("--output", required=True)
    parser.add_argument("--role", required=True, choices=("baseline", "candidate"))
    parser.add_argument("--case", nargs="+", choices=CASES)
    parser.add_argument("--graph-classpath", help="Use the registered 33k probe with compiled first-party tests and JUnit")
    args = parser.parse_args()
    try:
        return execute(args)
    except (KeyError, OSError, ValueError, subprocess.CalledProcessError) as failure:
        print(f"Passive measurement stopped: {type(failure).__name__}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
