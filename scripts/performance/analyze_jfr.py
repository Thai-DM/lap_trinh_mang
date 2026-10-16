"""Summarize sampled JFR evidence; samples are not exact allocation/CPU accounting."""
import argparse
import datetime
from collections import Counter
import json
from pathlib import Path
import re
import subprocess


def iter_events(stream):
    """Read JDK JSON event objects incrementally; never load the full export into RAM."""
    decoder, buffer, found = json.JSONDecoder(), "", False
    while True:
        chunk = stream.read(65536)
        buffer += chunk
        if not found:
            marker = re.search(r'"events"\s*:\s*\[', buffer)
            if marker:
                buffer, found = buffer[marker.end():], True
        if found:
            while True:
                buffer = buffer.lstrip(" \t\r\n,")
                if buffer.startswith("]"):
                    return
                try:
                    event, end = decoder.raw_decode(buffer)
                except json.JSONDecodeError:
                    break
                yield event
                buffer = buffer[end:]
        if not chunk:
            raise ValueError("Incomplete JFR JSON event stream")
        if len(buffer) > 16 * 1024 * 1024:
            raise ValueError("Single event exceeds the 16 MiB parsing guard")


def duration_seconds(value):
    if isinstance(value, (int, float)):
        return value
    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?", value or "")
    return (int(match[1] or 0) * 3600 + int(match[2] or 0) * 60 + float(match[3] or 0)) if match else 0


def analyze(events):
    cpu, native, allocations, allocation_application, application, counts = Counter(), Counter(), Counter(), Counter(), Counter(), Counter()
    pauses, monitors, writes = [], [], []
    for event in events:
        kind, values = event["type"], event["values"]
        counts[kind] += 1
        frames = (values.get("stackTrace") or {}).get("frames", [])
        stack = []
        for frame in frames:
            method = frame.get("method", {})
            owner = method.get("type", {}).get("name", "").replace("/", ".")
            stack.append(owner + "." + method.get("name", ""))
        if kind == "jdk.NativeMethodSample" and stack:
            native[stack[0]] += 1
        if kind == "jdk.ExecutionSample":
            if stack:
                cpu[stack[0]] += 1
            for method in stack:
                if method.startswith("com.smashprofs."):
                    application[method] += 1
                    break
        if kind == "jdk.ObjectAllocationSample":
            allocations[values.get("objectClass", {}).get("name", "unknown")] += values.get("weight", 0)
            for method in stack:
                if method.startswith("com.smashprofs."):
                    allocation_application[method] += values.get("weight", 0)
                    break
        if kind == "jdk.GCPhasePause":
            pauses.append(duration_seconds(values.get("duration")) * 1000)
        if kind == "jdk.JavaMonitorEnter":
            monitors.append(duration_seconds(values.get("duration")) * 1000)
        if kind == "jdk.SocketWrite":
            writes.append(duration_seconds(values.get("duration")) * 1000)
    def stats(values):
        ordered = sorted(values)
        return {"observedEvents": len(ordered), "maxMs": max(ordered, default=0),
                "p99Ms": ordered[max(0, int(len(ordered)*.99+.999999)-1)] if ordered else None}
    return {"eventCounts": counts, "executionTopFrameSamples": cpu.most_common(15), "nativeTopFrameSamples": native.most_common(10),
            "firstApplicationFrameSamples": application.most_common(20),
            "sampledAllocationWeightByFirstApplicationFrame": allocation_application.most_common(20),
            "sampledAllocationWeightByClass": allocations.most_common(20),
            "gcPauseEvents": stats(pauses), "monitorEnterEvents": stats(monitors), "socketWriteEvents": stats(writes),
            "limitations": "Thresholded/sampled events. Zero observed monitor or socket events does not prove zero blocking; allocation weights are sampled estimates."}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("recording", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--start-millis", type=int)
    parser.add_argument("--duration", type=float)
    args = parser.parse_args()
    executable = args.java_home / "bin" / ("jfr.exe" if __import__("os").name == "nt" else "jfr")
    process = subprocess.Popen([str(executable), "-J-Xmx64m", "print", "--json", "--stack-depth", "32", "--events",
                        "jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ObjectAllocationSample,jdk.GCPhasePause,jdk.JavaMonitorEnter,jdk.SocketWrite",
                        str(args.recording)], stdout=subprocess.PIPE, text=True, encoding="utf-8")
    try:
        events = iter_events(process.stdout)
        if args.start_millis is not None:
            if args.duration is None or args.duration <= 0:
                parser.error("A measurement start requires a positive --duration")
            begin = args.start_millis / 1000
            end = begin + args.duration
            events = (event for event in events if begin <= datetime.datetime.fromisoformat(
                re.sub(r"(\.\d{6})\d+", r"\1", event["values"]["startTime"].replace("Z", "+00:00"))).timestamp() < end)
        result = analyze(events)
        if process.wait() != 0:
            raise RuntimeError("jfr print failed")
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
    result["window"] = {"startMillis": args.start_millis, "durationSeconds": args.duration,
                        "selection": "event start timestamp; crossing durations are not clipped"}
    args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(args.output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
