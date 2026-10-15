"""Guarded experiments with separate warmup/measurement, immutable run directories and owned JVM cleanup."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import runpy
import secrets
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "final-project"
HELPERS = runpy.run_path(str(ROOT / "scripts/scalability/benchmark.py"))
request, available_memory, process_rss, write_properties = (HELPERS[k] for k in ("request", "available_memory", "process_rss", "write_properties"))
MAIN = "com.smashprofs.server.cluster.ClusterMain"


def metrics(url, secret):
    return request(url, "/measurement/snapshot", secret)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--java-home", type=Path, required=True)
    p.add_argument("--clients", default="4")
    p.add_argument("--profiles", default="IDLE")
    p.add_argument("--variants", default="baseline,shared")
    p.add_argument("--workers", type=int, choices=(1, 2, 3), default=1)
    p.add_argument("--algorithm", choices=("ROUND_ROBIN", "LOAD_AWARE"), default="LOAD_AWARE")
    p.add_argument("--snapshot-mode", choices=("BASELINE", "ADAPTIVE"), default="BASELINE")
    p.add_argument("--warmup", type=int, default=10)
    p.add_argument("--seconds", type=int, default=45)
    p.add_argument("--repeats", type=int, default=2)
    p.add_argument("--heap-mb", type=int, default=128)
    p.add_argument("--jfr", action="store_true")
    p.add_argument("--runtime", type=Path, default=PROJECT / "build/cluster")
    p.add_argument("--output", type=Path)
    args = p.parse_args()
    if args.workers == 1 and args.snapshot_mode == "ADAPTIVE":
        p.error("Adaptive policy currently runs in WorkerRuntime; use --workers 2 or 3")
    if args.workers == 1 and "CHURN" in args.profiles.split(","):
        p.error("CHURN requires cluster cleanup; use --workers 2 or 3")
    if args.repeats < 1:
        p.error("Require at least one repeat")
    if not (0 <= args.warmup <= 60 and 1 <= args.seconds <= 90 and args.seconds + args.warmup <= 100 and 96 <= args.heap_mb <= 256):
        p.error("Require warmup 0..60s, duration 1..90s, total <=100s/match, JVM heap 96..256 MB")
    run_id = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=7))).strftime("%Y%m%d-%H%M%S-%f")
    output = args.output or ROOT / "docs/performance/runs" / run_id
    output.mkdir(parents=True, exist_ok=False)
    work = PROJECT / "build/performance" / run_id
    work.mkdir(parents=True, exist_ok=False)
    java = str(args.java_home / "bin" / ("java.exe" if os.name == "nt" else "java"))
    runtime = args.runtime.resolve()
    source_hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                     for path in (PROJECT / "server/src").rglob("*.java")}
    environment = {"runId": run_id, "timestamp": datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=7))).isoformat(),
                   "platform": platform.platform(), "processor": platform.processor(), "logicalProcessors": os.cpu_count(),
                   "java": subprocess.run([java, "-version"], capture_output=True, text=True).stderr,
                   "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                   "gitStatus": subprocess.check_output(["git", "status", "--short"], cwd=ROOT, text=True),
                   "sourceSha256": source_hashes, "jarSha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in runtime.glob("*.jar")},
                   "configuration": {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
                   "memoryGuard": {"minimumStartMB": max(1500, (args.workers + (1 if args.workers == 1 else 2)) * args.heap_mb * 2 + 750),
                                   "minimumRunMB": 750}, "isolation": "loopback; server and bots share host; fresh JVMs per data point"}
    (output / "environment.json").write_text(json.dumps(environment, indent=2), encoding="utf-8")
    summaries = []
    for count in map(int, args.clients.split(",")):
        if count < 2 or count > 1000 or count % 2:
            raise ValueError("Even clients 2..1000")
        for profile in args.profiles.split(","):
            # Alternate A/B order between repeats to reduce run-order bias.
            for repeat in range(1, args.repeats + 1):
                variants = args.variants.split(",")
                if repeat % 2 == 0:
                    variants.reverse()
                for variant in variants:
                    if variant not in ("baseline", "shared", "indexed", "final"):
                        raise ValueError("Unknown variant")
                    free = available_memory()
                    if free is None or free < environment["memoryGuard"]["minimumStartMB"] * 1_000_000:
                        stopped = {"status": "BLOCKED_MEMORY", "freeBytes": free, "clients": count, "variant": variant,
                                   "guard": environment["memoryGuard"], "completedDataPoints": len(summaries)}
                        (output / "safety-stop.json").write_text(json.dumps(stopped, indent=2), encoding="utf-8")
                        print(json.dumps(stopped), flush=True)
                        return
                    label = f"{profile}-{count}-{variant}-{repeat}"
                    case = work / label
                    case.mkdir()
                    evidence = output / label
                    evidence.mkdir()
                    secret = secrets.token_urlsafe(32)
                    env = dict(os.environ, SMASHPROFS_CLUSTER_SECRET=secret)
                    is_cluster = args.workers > 1
                    base_url = "http://127.0.0.1:38080" if is_cluster else "http://127.0.0.1:39200"
                    endpoint = base_url if is_cluster else "tcp://127.0.0.1:39000"
                    config = {"http.port": 38080, "tcp.port": 39000, "admin.port": 39200,
                              "workers.initial": args.workers, "workers.min": args.workers, "workers.max": args.workers,
                              "worker.tcp.base": 39000, "worker.admin.base": 39100, "worker.heap": f"{args.heap_mb}m",
                              "max.rooms": 64, "max.connections": max(64, count + 4), "allocation.algorithm": args.algorithm,
                              "snapshot.mode": args.snapshot_mode, "snapshot.hz": 20, "snapshot.min.hz": 10,
                              "performance.sharedSnapshots": str(variant != "baseline").lower(),
                              "performance.indexedRecipients": str(variant in ("indexed", "final")).lower(),
                              "performance.stableRosters": str(variant == "final").lower(),
                              "run.dir": case / "workers", "history.dir": case / "history", "shutdown.grace.seconds": 0,
                              "worker.jfr.dir": evidence if args.jfr else ""}
                    config_path = case / "server.properties"
                    write_properties(config_path, config)
                    (evidence / "configuration.json").write_text(json.dumps({k: str(v) for k, v in config.items()}, indent=2), encoding="utf-8")
                    vm = [java, "-Xms32m", f"-Xmx{args.heap_mb}m", "-XX:NativeMemoryTracking=summary"]
                    for key in ("sharedSnapshots", "indexedRecipients", "stableRosters"):
                        vm.append(f"-Dsmashprofs.performance.{key}={config['performance.' + key]}")
                    if args.jfr and not is_cluster:
                        vm.append(f"-XX:StartFlightRecording=settings=profile,maxsize=16m,dumponexit=true,filename={evidence / 'worker.jfr'}")
                    server = generator = None
                    try:
                        print("RUN", label, flush=True)
                        with (evidence / "server.log").open("w", encoding="utf-8") as log:
                            server = subprocess.Popen(vm + ["-cp", str(runtime / "*"), MAIN, "control" if is_cluster else "standalone", str(config_path)],
                                                      cwd=PROJECT, env=env, stdout=log, stderr=subprocess.STDOUT)
                        deadline = time.monotonic() + 25
                        while True:
                            if server.poll() is not None:
                                raise RuntimeError("Server failed readiness")
                            try:
                                ready = request(base_url)
                                if len(ready.get("workers", [])) == args.workers:
                                    break
                            except (OSError, ValueError):
                                pass
                            if time.monotonic() > deadline:
                                raise TimeoutError("Readiness timeout")
                            time.sleep(.1)
                        admin_urls = [f"http://127.0.0.1:{w['report']['adminPort']}" for w in ready["workers"]] if is_cluster else [base_url]
                        result_file = evidence / "load.json"
                        generator_args = [java, "-Xms32m", f"-Xmx{args.heap_mb}m", f"-Dsmashprofs.load.profile={profile}",
                                          f"-Dsmashprofs.load.warmupSeconds={args.warmup}",
                                          "-Dsmashprofs.load.metricsEndpoints=" + ",".join(admin_urls),
                                          "-cp", str(runtime / "*"), MAIN, "load", endpoint, str(count), str(args.seconds), str(result_file)]
                        with (evidence / "generator.log").open("w", encoding="utf-8") as log:
                            generator = subprocess.Popen(generator_args, cwd=PROJECT, env=env, stdout=log, stderr=subprocess.STDOUT)
                        samples = []
                        deadline = time.monotonic() + args.seconds + args.warmup + 45
                        while generator.poll() is None:
                            free = available_memory()
                            if free is None or free < 750_000_000:
                                raise MemoryError("Safety guard: available RAM below 750 MB or unavailable")
                            for admin_url in admin_urls:
                                report = metrics(admin_url, secret)
                                # Remove per-room IDs from periodic samples; full boundary reports remain in load.json.
                                report.pop("roomTicks", None)
                                report.pop("rooms", None)
                                samples.append({"atMillis": int(time.time() * 1000), "worker": report,
                                                "rssBytes": process_rss(report["pid"])})
                            if time.monotonic() > deadline:
                                raise TimeoutError("Generator exceeded deadline")
                            time.sleep(1)
                        (evidence / "samples.json").write_text(json.dumps(samples, indent=2), encoding="utf-8")
                        data = json.loads(result_file.read_text(encoding="utf-8"))
                        reports = data.get("measurementWorkerMetrics", [])
                        correctness = generator.returncode == 0 and len(reports) == args.workers
                        summary = {"case": label, "clients": count, "workers": args.workers, "variant": variant,
                                   "profile": profile, "repeat": repeat, "correctness": "PASS" if correctness else "FAIL",
                                   "deliveriesPerClientPerSecond": data.get("deliveriesPerClientPerSecond"),
                                   "windowSeconds": [r.get("performance", {}).get("windowSeconds") for r in reports],
                                   "cycleHz": [r.get("cycleHz") for r in reports],
                                   "roomTick": [r["performance"]["stages"]["ROOM_TICK"] for r in reports],
                                   "workerCycle": [r["performance"]["stages"]["WORKER_CYCLE"] for r in reports],
                                   "schedulingLag": [r["performance"]["stages"]["SCHEDULING_LAG"] for r in reports],
                                   "snapshotEncodes": sum(r["performance"]["snapshotEncodes"] for r in reports),
                                   "snapshotCacheHits": sum(r["performance"]["snapshotCacheHits"] for r in reports),
                                   "peakRssBytes": max((s["rssBytes"] or 0 for s in samples), default=0)}
                        summary["performanceSlo"] = "PASS" if correctness and data.get("completedBeforeMeasurement", 0) == 0 and all(
                            r["performance"]["stages"]["WORKER_CYCLE"]["p99Ms"] < 16.666667 and 59 <= r["cycleHz"] <= 61 for r in reports) else "FAIL"
                        summary["sloScope"] = "Processing p99 and cycle frequency only; review schedulingLag and per-room metrics separately"
                        summary["deadlineSlo"] = "PASS" if correctness and all(
                            r["performance"]["stages"]["SCHEDULING_LAG"]["p99Ms"] < 16.666667
                            and r["performance"]["stages"]["SCHEDULING_LAG"]["budgetExceedRatio"] <= .01 for r in reports) else "FAIL"
                        (evidence / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
                        summaries.append(summary)
                        (output / "summary.json").write_text(json.dumps(summaries, indent=2), encoding="utf-8")
                        print(json.dumps(summary), flush=True)
                        if not correctness:
                            raise RuntimeError("Correctness failed; no further load increase")
                    except Exception as error:
                        (evidence / "failure.json").write_text(json.dumps({"status": "BLOCKED_OR_FAILED", "error": str(error)}, indent=2), encoding="utf-8")
                        print("STOP", str(error), flush=True)
                        return
                    finally:
                        if generator and generator.poll() is None:
                            generator.terminate()
                            generator.wait(timeout=10)
                        if server and server.poll() is None:
                            try:
                                request(base_url, "/admin/shutdown", secret)
                                server.wait(timeout=20)
                            except (OSError, ValueError, subprocess.TimeoutExpired):
                                server.terminate()
                                server.wait(timeout=10)
                                # Owned workers fence themselves after control loss.
                                time.sleep(5)
                        time.sleep(.5)


if __name__ == "__main__":
    main()
