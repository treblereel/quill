#!/usr/bin/env python3
"""Profile K2 worker stages with generated modules and verified source invalidation.

Build first: ./mvnw -q -pl quill-kotlin-semantic -am package -DskipTests
This measures the compiler worker, excluding MCP, indexing and JVM launch from stage timings.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import statistics
import struct
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET


def positive(value: str) -> int:
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("must be positive")
    return number


def read_exact(stream, size: int) -> bytes:
    data = bytearray()
    while len(data) < size:
        chunk = stream.read(size - len(data))
        if not chunk:
            raise RuntimeError("Worker closed its output (check JVM stderr)")
        data.extend(chunk)
    return bytes(data)


def query(worker, request: dict, timeout: int) -> dict:
    payload = json.dumps(request).encode("utf-8")
    if len(payload) > 1024 * 1024:
        raise ValueError("Request exceeds the worker's 1 MiB frame limit")
    timer = threading.Timer(timeout, worker.kill)
    timer.daemon = True
    timer.start()
    try:
        started = time.perf_counter()
        worker.stdin.write(struct.pack(">I", len(payload)) + payload)
        worker.stdin.flush()
        size, = struct.unpack(">I", read_exact(worker.stdout, 4))
        if not 0 < size <= 1024 * 1024:
            raise RuntimeError(f"Invalid worker response size: {size}")
        result = json.loads(read_exact(worker.stdout, size))
        result["roundtrip_ms"] = (time.perf_counter() - started) * 1000
        return result
    finally:
        timer.cancel()


def verify(result: dict, descriptor: str, reused: bool) -> None:
    if (result.get("status") != "resolved" or result.get("descriptor") != descriptor
            or result.get("analysis_reused") is not reused):
        raise RuntimeError(f"Unexpected semantic result: {result}")
    timings = result.get("analysis_timings_ms", {})
    required = {"capture_before", "capture_after", "lookup", "total"}
    if not reused:
        required |= {"configuration", "frontend", "dispose"}
    if not required <= timings.keys():
        raise RuntimeError(f"Missing stage timings: {result}")
    if reused and ("configuration" in timings or "frontend" in timings):
        raise RuntimeError(f"Reused query ran the frontend: {result}")


def summarize(samples: list[dict]) -> dict:
    phases = sorted({phase for sample in samples for phase in sample["analysis_timings_ms"]})
    return {"roundtrip_median_ms": statistics.median(s["roundtrip_ms"] for s in samples),
            "stage_medians_ms": {phase: statistics.median(s["analysis_timings_ms"].get(phase, 0)
                                                       for s in samples) for phase in phases},
            "samples": samples}


def benchmark(args, files: int, stdlib: Path) -> dict:
    with tempfile.TemporaryDirectory(prefix="quill K2 stages ") as directory:
        module = Path(directory)
        sources = []
        for index in range(files):
            source = module / f"Source{index:05d}.kt"
            # Cross-file references require the complete module to be available.
            source.write_text("package probe\n" + "\n".join(
                f"fun f{index}_{fn}(x: Int): Int = " +
                (f"f{index - 1}_0(x) + {fn}" if index else f"x + {fn}")
                for fn in range(args.functions_per_file)), encoding="utf-8")
            sources.append(str(source))
        definitions = module / "Definitions.kt"
        definitions.write_text("package probe\nfun pick() = 1000", encoding="utf-8")
        caller = module / "Caller.kt"
        body = f"package probe\nfun run() = pick()\nfun chain() = f{files - 1}_0(1)"
        caller.write_text(body, encoding="utf-8")
        request = {"module": str(module), "source_set": "main", "file": str(caller),
                   "offset": body.index("pick()"), "sources": sources + [str(definitions), str(caller)],
                   "java_roots": [], "classpath": [str(stdlib)], "profile_analysis": True}
        java = str(args.java_home / "bin" / ("java.exe" if os.name == "nt" else "java")) if args.java_home else "java"
        environment = dict(os.environ)
        if args.java_home:
            environment["JAVA_HOME"] = str(args.java_home.resolve())
        with tempfile.TemporaryFile() as stderr:
            worker = subprocess.Popen([java, "-Xmx512m", "-jar", str(args.worker.resolve()), "--serve"],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr, env=environment)
            try:
                cold = query(worker, request, args.timeout)
                verify(cold, "()I", False)
                changed = []
                for i in range(args.samples):
                    boolean = i % 2 == 0
                    definitions.write_text("package probe\nfun pick() = " + ("true" if boolean else "1000"), encoding="utf-8")
                    result = query(worker, request, args.timeout)
                    verify(result, "()Z" if boolean else "()I", False)
                    previous = changed[-1] if changed else cold
                    if result["analysis_generation"] <= previous["analysis_generation"]:
                        raise RuntimeError("Source edit did not advance the FIR generation")
                    changed.append(result)
                unchanged = []
                descriptor = changed[-1]["descriptor"]
                for _ in range(args.samples):
                    result = query(worker, request, args.timeout)
                    verify(result, descriptor, True)
                    if result["analysis_generation"] != changed[-1]["analysis_generation"]:
                        raise RuntimeError("Unchanged inputs did not reuse the FIR generation")
                    unchanged.append(result)
                return {"generated_files": files, "total_sources": files + 2,
                        "functions_per_generated_file": args.functions_per_file,
                        "cold": cold, "changed": summarize(changed), "unchanged": summarize(unchanged)}
            except Exception:
                worker.kill()
                worker.wait()
                stderr.seek(0)
                print(stderr.read().decode("utf-8", errors="replace"), file=sys.stderr)
                raise
            finally:
                worker.stdin.close()
                try:
                    worker.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    worker.kill()
                    worker.wait()
                worker.stdout.close()


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worker", type=Path, default=root / "quill-kotlin-semantic/target/quill-kotlin-semantic-0.1-SNAPSHOT-all.jar")
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--stdlib", type=Path)
    parser.add_argument("--files", type=positive, nargs="+", default=[10, 100, 500])
    parser.add_argument("--functions-per-file", type=positive, default=5)
    parser.add_argument("--samples", type=positive, default=10)
    parser.add_argument("--timeout", type=positive, default=30)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    version = ET.parse(root / "pom.xml").findtext("{*}properties/{*}kotlin.version")
    stdlib = (args.stdlib or Path.home() / f".m2/repository/org/jetbrains/kotlin/kotlin-stdlib/{version}/kotlin-stdlib-{version}.jar").resolve(strict=True)
    args.worker.resolve(strict=True)
    java = str(args.java_home / "bin" / ("java.exe" if os.name == "nt" else "java")) if args.java_home else "java"
    report = {"compiler_version": version,
              "worker_java": subprocess.run([java, "-version"], capture_output=True, text=True, check=True).stderr.strip(),
              "scope": "worker only; stage times exclude JVM launch and protocol serialization",
              "modules": []}
    for count in args.files:
        print(f"Profiling {count + 2} sources on {java}", file=sys.stderr, flush=True)
        report["modules"].append(benchmark(args, count, stdlib))
    output = json.dumps(report, indent=2)
    if args.output:
        args.output.write_text(output + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
