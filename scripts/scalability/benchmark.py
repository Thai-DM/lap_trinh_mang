"""Standard-library benchmark runner. Build with :server:installCluster first.

Every data point starts fresh JVMs, measures actual TCP bots, samples all worker
metrics, and requests shutdown in finally. No extrapolated measurements.
"""
import argparse
import csv
import ctypes
import json
import os
from pathlib import Path
import platform
import secrets
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "final-project"
MAIN = "com.smashprofs.server.cluster.ClusterMain"


def request(base, path="/metrics", secret=None):
    headers = {"Content-Type": "application/json"}
    if secret:
        headers["Authorization"] = secret
    req = urllib.request.Request(base + path, data=b"{}", headers=headers)
    with urllib.request.urlopen(req, timeout=3) as response:
        return json.load(response)


def available_memory():
    if os.name == "nt":
        class Memory(ctypes.Structure):
            _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
                (name, ctypes.c_ulonglong) for name in
                ("total", "available", "page_total", "page_available", "virtual", "virtual_available", "extended")]
        memory = Memory()
        memory.length = ctypes.sizeof(memory)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(memory)):
            return memory.available
    elif Path("/proc/meminfo").exists():
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) * 1024
    return None


def process_rss(pid):
    try:
        if os.name == "nt":
            class Counters(ctypes.Structure):
                _fields_ = [("cb", ctypes.c_ulong), ("faults", ctypes.c_ulong)] + [
                    (name, ctypes.c_size_t) for name in ("peak", "working", "qpp", "qpu", "qnp", "qnu", "page", "peakpage")]
            kernel = ctypes.windll.kernel32
            kernel.OpenProcess.restype = ctypes.c_void_p
            handle = kernel.OpenProcess(0x0410, False, int(pid))
            if not handle:
                return None
            try:
                counters = Counters()
                counters.cb = ctypes.sizeof(counters)
                if ctypes.windll.psapi.GetProcessMemoryInfo(ctypes.c_void_p(handle), ctypes.byref(counters), counters.cb):
                    return counters.working
            finally:
                kernel.CloseHandle(ctypes.c_void_p(handle))
        else:
            return int(Path(f"/proc/{pid}/statm").read_text().split()[1]) * os.sysconf("SC_PAGE_SIZE")
    except (OSError, ValueError):
        pass
    return None


def write_properties(path, values):
    # Use forward slashes so java.util.Properties does not interpret Windows escapes.
    path.write_text("\n".join(f"{k}={str(v).replace(chr(92), '/')}" for k, v in values.items()) + "\n", encoding="ascii")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"), required=not os.environ.get("JAVA_HOME"))
    parser.add_argument("--max-clients", type=int, default=100)
    parser.add_argument("--seconds", type=int, default=12)
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--scenarios", default="single,round-robin,load-aware,adaptive")
    parser.add_argument("--counts", default="2,4,16,32,64,100,500,1000")
    parser.add_argument("--minimum-start-memory-mb", type=int, default=1500)
    parser.add_argument("--minimum-run-memory-mb", type=int, default=750)
    parser.add_argument("--output", type=Path, default=ROOT / "docs/scalability/results")
    args = parser.parse_args()
    java = str(Path(args.java_home) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    classpath = str(PROJECT / "build/cluster/*")
    args.output.mkdir(parents=True, exist_ok=True)
    counts = [int(n) for n in args.counts.split(",") if int(n) <= args.max_clients]
    environment = {"platform": platform.platform(), "processor": platform.processor(), "logicalProcessors": os.cpu_count(),
                   "availableMemoryBytesAtStart": available_memory(), "jdk": subprocess.run([java, "-version"], capture_output=True, text=True).stderr,
                   "seconds": args.seconds, "repeats": args.repeats, "counts": counts, "loopback": True,
                   "date": time.strftime("%Y-%m-%d"), "isolation": "fresh JVMs for each data point; bots share this host"}
    environment.update(minimumStartMemoryMB=args.minimum_start_memory_mb, minimumRunMemoryMB=args.minimum_run_memory_mb)
    (args.output / "environment.json").write_text(json.dumps(environment, indent=2), encoding="utf-8")
    rows = []
    for scenario in args.scenarios.split(","):
        if scenario not in ("single", "round-robin", "load-aware", "adaptive"):
            raise ValueError("Unknown scenario " + scenario)
        overloaded = False
        for count in counts:
            if overloaded:
                break
            for repeat in range(1, args.repeats + 1):
                free = available_memory()
                if free is not None and free < args.minimum_start_memory_mb * 1_000_000:
                    reason = {"scenario": scenario, "clients": count, "repeat": repeat, "availableMemoryBytes": free,
                              "status": "SAFETY_STOP_BEFORE_START", "thresholdMB": args.minimum_start_memory_mb}
                    (args.output / (scenario + "-safety-stop.json")).write_text(json.dumps(reason, indent=2), encoding="utf-8")
                    print("Safety stop:", json.dumps(reason), flush=True)
                    overloaded = True
                    break
                label = f"{scenario}-{count}-{repeat}"
                run = PROJECT / "build/scalability/benchmark" / label
                run.mkdir(parents=True, exist_ok=True)
                secret = secrets.token_urlsafe(32)
                env = dict(os.environ, SMASHPROFS_CLUSTER_SECRET=secret)
                standalone = scenario == "single"
                port, admin, tcp_base, worker_admin_base = 18080, 19200, 19000, 19100
                url = f"http://127.0.0.1:{admin if standalone else port}"
                endpoint = "tcp://127.0.0.1:19000" if standalone else url
                config = {"http.port": port, "workers.initial": 3, "workers.min": 3, "workers.max": 3,
                          "autoscaling.enabled": "false", "allocation.algorithm": "ROUND_ROBIN" if scenario == "round-robin" else "LOAD_AWARE",
                          "snapshot.mode": "ADAPTIVE" if scenario == "adaptive" else "BASELINE", "snapshot.hz": 20,
                          "snapshot.min.hz": 5, "tcp.port": 19000, "admin.port": admin,
                          "worker.tcp.base": tcp_base, "worker.admin.base": worker_admin_base,
                          "max.connections": 1200, "max.rooms": 300, "worker.heap": "512m",
                          "run.dir": run / "workers", "history.dir": run / "history", "shutdown.grace.seconds": 0}
                config_path = run / "control.properties"
                write_properties(config_path, config)
                output = args.output / (label + ".json")
                samples, errors = [], []
                control = generator = None
                print("RUN", label, flush=True)
                try:
                    with (run / "control.log").open("w", encoding="utf-8") as log:
                        control = subprocess.Popen([java, "-Xms64m", "-Xmx512m", "-cp", classpath, MAIN,
                                                    "standalone" if standalone else "control", str(config_path)],
                                                   cwd=PROJECT, env=env, stdout=log, stderr=subprocess.STDOUT)
                    deadline = time.monotonic() + 25
                    while True:
                        if control.poll() is not None:
                            raise RuntimeError("Control process exited; inspect " + str(run / "control.log"))
                        try:
                            ready = request(url)
                            if len(ready.get("workers", [])) >= (1 if standalone else 3):
                                break
                        except (OSError, ValueError):
                            pass
                        if time.monotonic() > deadline:
                            raise TimeoutError("Cluster readiness timeout")
                        time.sleep(.1)
                    with (run / "generator.log").open("w", encoding="utf-8") as log:
                        generator = subprocess.Popen([java, "-Xmx512m", "-cp", classpath, MAIN, "load", endpoint, str(count), str(args.seconds), str(output)],
                                                     cwd=PROJECT, stdout=log, stderr=subprocess.STDOUT)
                    deadline = time.monotonic() + args.seconds + max(60, count * .8)
                    measured_start = time.monotonic()
                    while generator.poll() is None:
                        try:
                            state = request(url)
                            for worker in state.get("workers", []):
                                report = worker.get("report", {})
                                sample = {key: value for key, value in report.items() if key not in ("rooms", "incarnation")}
                                sample.update(workerId=worker["workerId"], sampleSeconds=time.monotonic() - measured_start)
                                rss = process_rss(report.get("pid", 0))
                                if rss is not None:
                                    sample["rssBytes"] = rss
                                samples.append(sample)
                        except (OSError, ValueError) as error:
                            errors.append(type(error).__name__)
                        if time.monotonic() > deadline:
                            raise TimeoutError("Load generator deadline")
                        free = available_memory()
                        if free is not None and free < args.minimum_run_memory_mb * 1_000_000:
                            raise MemoryError(f"Safety stop: available RAM below {args.minimum_run_memory_mb} MB")
                        time.sleep(1)
                    result = json.loads(output.read_text(encoding="utf-8")) if output.exists() else {}
                    result["samples"] = samples
                    result["scenario"] = scenario
                    result["repeat"] = repeat
                    result["generatorExitCode"] = generator.returncode
                    result["samplingErrors"] = errors
                    output.write_text(json.dumps(result, indent=2), encoding="utf-8")
                    row = summarize(result, count, scenario, repeat)
                    rows.append(row)
                    print(json.dumps(row), flush=True)
                    if generator.returncode != 0:
                        overloaded = True
                        break
                except Exception as error:
                    output.write_text(json.dumps({"scenario": scenario, "clients": count, "repeat": repeat,
                                                  "error": str(error), "samples": samples}, indent=2), encoding="utf-8")
                    print("SAFETY STOP", label, str(error), flush=True)
                    overloaded = True
                    break
                finally:
                    if generator and generator.poll() is None:
                        generator.terminate()
                        generator.wait(timeout=10)
                    if control and control.poll() is None:
                        try:
                            request(url, "/admin/shutdown", secret)
                            control.wait(timeout=20)
                        except (OSError, ValueError, subprocess.TimeoutExpired):
                            control.terminate()
                            control.wait(timeout=10)
                    # Workers self-fence if the supervisor was unable to run graceful cleanup.
                    time.sleep(.3)
                if rows:
                    with (args.output / "summary.csv").open("w", newline="", encoding="utf-8") as stream:
                        writer = csv.DictWriter(stream, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
    if rows:
        with (args.output / "summary.csv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)


def summarize(result, count, scenario, repeat):
    samples = result.get("samples", [])
    # Steady-state samples: all requested connections present and at least 3 seconds after initial sample.
    by_time = {}
    for sample in samples:
        key = int(sample["sampleSeconds"])
        by_time.setdefault(key, []).append(sample)
    steady = [group for second, group in by_time.items() if second >= 3 and sum(s.get("activeConnections", 0) for s in group) == count]
    flat = [sample for group in steady for sample in group]
    # Maximum worker percentile is conservative; it is not a pooled cluster percentile.
    worst_p95 = max((s.get("tickP95Ms", 0) for s in flat), default=0)
    worst_p99 = max((s.get("tickP99Ms", 0) for s in flat), default=0)
    return {"scenario": scenario, "clients": count, "repeat": repeat, "exitCode": result.get("generatorExitCode", -1),
            "startedClients": result.get("startedClients", 0), "failureCount": result.get("connectionFailures", 0),
            "protocolErrors": result.get("protocolErrors", 0), "unexpectedDisconnects": result.get("unexpectedDisconnects", 0),
            "deliveriesPerSecond": result.get("snapshotDeliveriesPerSecond", 0),
            "deliveriesPerClientPerSecond": result.get("deliveriesPerClientPerSecond", 0),
            "receivedBytesPerSecond": result.get("receivedBytesPerSecond", 0), "worstWorkerP95Ms": worst_p95, "worstWorkerP99Ms": worst_p99,
            "peakWorkerCpu": max((s.get("cpu", 0) for s in flat), default=0),
            "peakTotalHeapBytes": max((sum(s.get("heapBytes", 0) for s in group) for group in steady), default=0),
            "peakTotalRssBytes": max((sum(s.get("rssBytes", 0) for s in group) for group in steady), default=0),
            "peakRooms": max((sum(s.get("activeRooms", 0) for s in group) for group in steady), default=0),
            "totalTickOverruns": sum(group.get("tickOverruns", 0) for group in (steady[-1] if steady else [])),
            "totalCycles": sum(group.get("cycles", 0) for group in (steady[-1] if steady else []))}


if __name__ == "__main__":
    main()
