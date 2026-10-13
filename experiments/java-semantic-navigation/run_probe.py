#!/usr/bin/env python3
"""Measure an isolated javac position model on Quill's real String/Path overload."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from quill_benchmark import latency_summary, process_tree_rss_kib


def run(java_home, source, source_root, classpath, release_level, classes, samples):
    text = source.read_text(encoding="utf-8")
    anchor = "return isSourceFile(path.getFileName().toString());"
    if text.count(anchor) != 1:
        raise ValueError("Real-source query anchor must be unique")
    prefix = text[:text.index(anchor)] + "return "
    offset = len(prefix.encode("utf-16-le")) // 2
    argv = [str(java_home / "bin/java"), "-Xmx512m", "-cp", str(classes),
            "org.treblereel.mcp.experiment.ProbeMain", str(source_root), classpath, release_level]
    started = time.perf_counter()
    process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, encoding="utf-8", bufsize=1)
    messages = queue.Queue()
    def read():
        for line in process.stdout:
            messages.put(line.strip())
        messages.put(None)
    threading.Thread(target=read, daemon=True).start()
    threading.Thread(target=lambda: list(process.stderr), daemon=True).start()
    stop = threading.Event()
    rss = []
    def sample_memory():
        while not stop.is_set():
            value = process_tree_rss_kib(process.pid)
            if value is not None:
                rss.append(value)
            stop.wait(0.05)
    sampler = threading.Thread(target=sample_memory, daemon=True)
    sampler.start()
    forced = False
    rows = []
    try:
        if messages.get(timeout=30) != "READY":
            raise RuntimeError("Probe startup failed")
        startup = (time.perf_counter()-started)*1000
        for _ in range(samples):
            process.stdin.write(base64.b64encode(str(source).encode()).decode()+"\t"+str(offset)+"\n")
            process.stdin.flush()
            response = messages.get(timeout=30)
            if response is None:
                raise RuntimeError("Unexpected worker EOF")
            parts = response.split("\t")
            if len(parts) != 9:
                raise RuntimeError("Invalid probe result")
            def decode(value):
                return None if value == "-" else base64.b64decode(value).decode("utf-8")
            row = dict(status=parts[0], owner=decode(parts[1]), kind=decode(parts[2]),
                       name=decode(parts[3]), descriptor=decode(parts[4]), generation=int(parts[5]),
                       reused=parts[6] == "true", analysis_ms=float(parts[7]), heap_bytes=int(parts[8]))
            rows.append(row)
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            forced = True
            process.kill()
            process.wait(timeout=5)
        stop.set()
        sampler.join(timeout=2)
    release = java_home / "release"
    return dict(java_home=str(java_home), jdk_release_sha256=hashlib.sha256(release.read_bytes()).hexdigest(),
                startup_ms=startup, first_analysis_ms=rows[0]["analysis_ms"],
                warm_latency=latency_summary([row["analysis_ms"] for row in rows[1:]]),
                peak_sampled_rss_kib=max(rss) if rss else None, rows=rows,
                clean_eof=process.returncode == 0 and not forced,
                passed=process.returncode == 0 and not forced and all(
                    row["status"] == "resolved" and row["owner"] == "org.treblereel.mcp.core.JvmSourceFiles"
                    and row["descriptor"] == "(Ljava/lang/String;)Z" and row["generation"] == 1
                    and row["reused"] == (i > 0) for i, row in enumerate(rows)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, action="append", required=True)
    parser.add_argument("--samples", type=int, default=21)
    parser.add_argument("--source-root", type=Path)
    parser.add_argument("--classpath-file", type=Path)
    parser.add_argument("--release", default="17")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.samples < 2:
        parser.error("At least two samples are needed")
    source = ROOT / "quill-core/src/main/java/org/treblereel/mcp/core/JvmSourceFiles.java"
    source_root = args.source_root.resolve() if args.source_root else source
    classpath = args.classpath_file.read_text().strip() if args.classpath_file else ""
    classes = Path(__file__).resolve().parent / "target/classes"
    if not classes.is_dir():
        parser.error("Build the experiment before running the probe")
    report = dict(schema_version=1, source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                  source_root=str(source_root), release_level=args.release,
                  source_files=len(list(source_root.rglob("*.java"))) if source_root.is_dir() else 1,
                  classpath_entries=len(classpath.split(__import__("os").pathsep)) if classpath else 0,
                  additional_runtime_dependency_jars=0, production_backend_enabled=False,
                  native_size_growth_bytes=None,
                  limitations=["One selected real source position; not exhaustive navigation coverage",
                               "Content fingerprinting scans every configured source and classpath input",
                               "Not integrated with production MCP, compiler-settings capture, or native distribution"],
                  runs=[])
    for home in args.java_home:
        report["runs"].append(run(home.resolve(), source, source_root, classpath, args.release, classes, args.samples))
    report["all_passed"] = all(run["passed"] for run in report["runs"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"all_passed": report["all_passed"], "runs": [{k:r[k] for k in
        ("first_analysis_ms", "warm_latency", "peak_sampled_rss_kib", "clean_eof")} for r in report["runs"]]}))
    return 0 if report["all_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
