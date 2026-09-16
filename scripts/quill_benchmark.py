#!/usr/bin/env python3
"""Informational cold-index and MCP latency benchmark for Quill."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
from pathlib import Path
import platform
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from typing import Any


DEFAULT_CONCURRENCY = (4, 16, 100)
TOOLS = (
    ("get_overview", {}),
    ("search_classes", {"pattern": "*Service", "limit": 25}),
    ("find_git_hotspots", {"limit": 25}),
    ("list_beans", {"limit": 25}),
    ("get_recent_changes", {"commits": 10}),
)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True,
                        help="Maven or Gradle project to benchmark")
    parser.add_argument("--quill", default=os.environ.get("QUILL_BIN", "quill"),
                        help="Quill binary (default: QUILL_BIN or quill on PATH)")
    parser.add_argument("--concurrency", default="4,16,100",
                        help="Comma-separated MCP burst sizes")
    parser.add_argument("--warmup", type=int, default=5,
                        help="Sequential MCP warmup calls")
    parser.add_argument("--samples-per-tool", type=int, default=10,
                        help="Sequential latency samples collected for each MCP tool")
    parser.add_argument("--request-timeout", type=int, default=30,
                        help="Seconds allowed per benchmark MCP response")
    parser.add_argument("--output", type=Path,
                        help="JSON output path (default: target/benchmarks/...)")
    parser.add_argument("--keep-benchmark-index", action="store_true",
                        help="Keep the newly built index instead of restoring the previous one")
    return parser.parse_args()


def parse_concurrency(value: str) -> list[int]:
    try:
        values = [int(item.strip()) for item in value.split(",") if item.strip()]
    except ValueError as exc:
        raise ValueError("--concurrency must contain positive integers") from exc
    if not values or any(value <= 0 for value in values):
        raise ValueError("--concurrency must contain positive integers")
    return values


def process_tree_rss_kib(root_pid: int) -> int | None:
    """Return aggregate resident memory for a process and its descendants."""
    if os.name == "nt":
        command = [
            "powershell", "-NoProfile", "-Command",
            "$p=Get-CimInstance Win32_Process;"
            f"$ids=@({root_pid});"
            "do{$old=$ids.Count;$ids+=@($p|?{$ids -contains $_.ParentProcessId}|% ProcessId);"
            "$ids=@($ids|select -Unique)}while($ids.Count-ne $old);"
            "[long](($p|?{$ids -contains $_.ProcessId}|measure WorkingSetSize -Sum).Sum)",
        ]
        try:
            return int(subprocess.check_output(
                command, text=True, stderr=subprocess.DEVNULL, timeout=2).strip()) // 1024
        except (OSError, ValueError, subprocess.SubprocessError):
            return None
    try:
        output = subprocess.check_output(
            ["ps", "-axo", "pid=,ppid=,rss="], text=True,
            stderr=subprocess.DEVNULL, timeout=2)
        rows = [tuple(map(int, line.split())) for line in output.splitlines()
                if len(line.split()) == 3]
        descendants = {root_pid}
        changed = True
        while changed:
            changed = False
            for pid, parent, _ in rows:
                if parent in descendants and pid not in descendants:
                    descendants.add(pid)
                    changed = True
        return sum(rss for pid, _, rss in rows if pid in descendants)
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def output_of(command: list[str], cwd: Path | None = None) -> str | None:
    try:
        return subprocess.check_output(
            command, cwd=cwd, text=True, stderr=subprocess.DEVNULL, timeout=5).strip()
    except (OSError, subprocess.SubprocessError):
        return None


def host_metadata() -> dict[str, Any]:
    total_memory: int | None = None
    try:
        total_memory = os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")
    except (AttributeError, OSError, ValueError):
        if os.name == "nt":
            memory = output_of([
                "powershell", "-NoProfile", "-Command",
                "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory",
            ])
            total_memory = int(memory) if memory and memory.isdigit() else None
    return {
        "platform": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor() or None,
        "cpu_count": os.cpu_count(),
        "memory_mib": round(total_memory / 1024 / 1024, 2) if total_memory else None,
        "python": platform.python_version(),
    }


def project_metadata(project: Path) -> dict[str, Any]:
    head = output_of(["git", "rev-parse", "HEAD"], project)
    status = output_of(["git", "status", "--porcelain"], project)
    return {
        "git_head": head,
        "worktree_dirty": bool(status) if status is not None else None,
    }


def run_measured(command: list[str], cwd: Path) -> dict[str, Any]:
    started = time.perf_counter()
    process = subprocess.Popen(command, cwd=cwd, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True)
    peak_rss_kib: int | None = None
    while process.poll() is None:
        current = process_tree_rss_kib(process.pid)
        if current is not None:
            peak_rss_kib = max(peak_rss_kib or 0, current)
        time.sleep(0.05 if os.name != "nt" else 0.25)
    stdout, stderr = process.communicate()
    return {
        "command": command,
        "duration_seconds": round(time.perf_counter() - started, 3),
        "peak_rss_mib": round(peak_rss_kib / 1024, 2) if peak_rss_kib else None,
        "exit_code": process.returncode,
        "stdout": stdout,
        "stderr": stderr,
    }


def parse_phase_timings(stderr: str) -> dict[str, int]:
    prefix = "[quill] Timings: "
    for line in stderr.splitlines():
        if not line.startswith(prefix):
            continue
        result: dict[str, int] = {}
        for value in line[len(prefix):].split(", "):
            phase, separator, millis = value.partition("=")
            if not separator or not millis.endswith("ms"):
                raise ValueError(f"Invalid Quill phase timing: {value}")
            result[phase] = int(millis[:-2])
        return result
    return {}


class McpClient:
    def __init__(self, command: list[str], project: Path, timeout_seconds: int):
        environment = os.environ.copy()
        environment["QUILL_MCP_REQUEST_TIMEOUT"] = str(timeout_seconds)
        self.process = subprocess.Popen(
            command + ["--mcp", "--project", str(project)], cwd=project,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, bufsize=1, env=environment)
        self.timeout_seconds = timeout_seconds
        self.responses: queue.Queue[dict[str, Any] | BaseException] = queue.Queue()
        self.stderr: list[str] = []
        self.reader = threading.Thread(target=self._read_stdout, daemon=True)
        self.error_reader = threading.Thread(target=self._read_stderr, daemon=True)
        self.reader.start()
        self.error_reader.start()

    def _read_stdout(self) -> None:
        assert self.process.stdout is not None
        try:
            for line in self.process.stdout:
                if line.strip():
                    self.responses.put(json.loads(line))
        except BaseException as exc:  # propagate malformed output to the benchmark thread
            self.responses.put(exc)

    def _read_stderr(self) -> None:
        assert self.process.stderr is not None
        self.stderr.extend(self.process.stderr)

    def send(self, request_id: int, method: str, params: dict[str, Any]) -> None:
        assert self.process.stdin is not None
        self.process.stdin.write(json.dumps({
            "jsonrpc": "2.0", "id": request_id, "method": method, "params": params,
        }, separators=(",", ":")) + "\n")

    def flush(self) -> None:
        assert self.process.stdin is not None
        self.process.stdin.flush()

    def receive(self, expected: set[int], timeout: float) -> dict[int, dict[str, Any]]:
        deadline = time.perf_counter() + timeout
        found: dict[int, dict[str, Any]] = {}
        while expected - found.keys():
            remaining = deadline - time.perf_counter()
            if remaining <= 0:
                raise TimeoutError(f"Missing MCP response ids: {sorted(expected - found.keys())}")
            item = self.receive_one(remaining)
            request_id = item.get("id")
            if request_id in expected:
                if request_id in found:
                    raise RuntimeError(f"Duplicate MCP response id: {request_id}")
                found[request_id] = item
        return found

    def receive_one(self, timeout: float) -> dict[str, Any]:
        try:
            item = self.responses.get(timeout=timeout)
        except queue.Empty as exc:
            raise TimeoutError("Timed out waiting for MCP response") from exc
        if isinstance(item, BaseException):
            raise RuntimeError("Invalid MCP stdout") from item
        return item

    def close(self) -> None:
        if self.process.stdin:
            try:
                self.process.stdin.close()
            except OSError:
                pass
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=5)
        self.reader.join(timeout=1)
        self.error_reader.join(timeout=1)


def percentile(values: list[float], percentage: float) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(percentage * len(ordered)) - 1)
    return ordered[index]


def latency_summary(values: list[float], errors: int = 0) -> dict[str, Any]:
    return {
        "samples": len(values),
        "p50_latency_ms": round(percentile(values, 0.50), 2),
        "p95_latency_ms": round(percentile(values, 0.95), 2),
        "max_latency_ms": round(max(values), 2),
        "errors": errors,
    }


def benchmark_mcp(command: list[str], project: Path, warmup: int,
                  samples_per_tool: int, concurrency: list[int],
                  timeout_seconds: int) -> dict[str, Any]:
    client = McpClient(command, project, timeout_seconds)
    next_id = 1
    result: dict[str, Any] | None = None
    try:
        startup = time.perf_counter()
        client.send(next_id, "initialize", {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "quill-benchmark", "version": "1"},
        })
        client.flush()
        initialized = client.receive({next_id}, timeout_seconds)[next_id]
        if "result" not in initialized:
            raise RuntimeError(f"MCP initialization failed: {initialized}")
        startup_seconds = time.perf_counter() - startup
        next_id += 1
        assert client.process.stdin is not None
        client.process.stdin.write(
            '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}\n')
        client.flush()

        for index in range(warmup):
            name, arguments = TOOLS[index % len(TOOLS)]
            client.send(next_id, "tools/call", {"name": name, "arguments": arguments})
            client.flush()
            client.receive({next_id}, timeout_seconds)
            next_id += 1

        tools = []
        for name, tool_arguments in TOOLS:
            latencies = []
            errors = 0
            for _ in range(samples_per_tool):
                started = time.perf_counter()
                client.send(next_id, "tools/call", {
                    "name": name, "arguments": tool_arguments,
                })
                client.flush()
                response = client.receive({next_id}, timeout_seconds)[next_id]
                latencies.append((time.perf_counter() - started) * 1000)
                if "error" in response or response.get("result", {}).get("isError") is True:
                    errors += 1
                next_id += 1
            tools.append({"tool": name, **latency_summary(latencies, errors)})

        batches = []
        for size in concurrency:
            ids = set(range(next_id, next_id + size))
            for offset, request_id in enumerate(sorted(ids)):
                name, tool_arguments = TOOLS[offset % len(TOOLS)]
                client.send(request_id, "tools/call", {
                    "name": name, "arguments": tool_arguments,
                })
            started = time.perf_counter()
            client.flush()
            completion: dict[int, float] = {}
            errors = 0
            deadline = started + timeout_seconds + max(5, size / 5)
            remaining = set(ids)
            while remaining:
                response = client.receive_one(max(0.1, deadline - time.perf_counter()))
                now = time.perf_counter()
                request_id = response.get("id")
                if request_id not in remaining:
                    continue
                completion[request_id] = (now - started) * 1000
                if "error" in response or response.get("result", {}).get("isError") is True:
                    errors += 1
                remaining.remove(request_id)
            latencies = list(completion.values())
            elapsed = max(latencies) / 1000
            batches.append({
                "concurrent_requests": size,
                "elapsed_seconds": round(elapsed, 3),
                "throughput_requests_per_second": round(size / elapsed, 2),
                **{key: value for key, value in latency_summary(latencies, errors).items()
                   if key != "samples"},
            })
            next_id += size
        result = {
            "startup_seconds": round(startup_seconds, 3),
            "warmup_requests": warmup,
            "samples_per_tool": samples_per_tool,
            "tools": tools,
            "batches": batches,
        }
    finally:
        client.close()
    assert result is not None
    result["stderr"] = "".join(client.stderr)
    return result


def index_size(project: Path) -> tuple[int, int]:
    databases = list((project / ".quill").glob("*.db"))
    size = sum(path.stat().st_size for path in databases)
    return len(databases), size


class IndexSandbox:
    """Keep a benchmark from changing the project's pre-existing index state."""

    def __init__(self, index: Path, keep_benchmark_index: bool):
        self.index = index
        self.keep_benchmark_index = keep_benchmark_index
        self.backup_root: Path | None = None

    def __enter__(self) -> "IndexSandbox":
        if self.index.exists() and not self.keep_benchmark_index:
            self.backup_root = Path(tempfile.mkdtemp(prefix="quill-index-backup-"))
            shutil.copytree(self.index, self.backup_root / ".quill")
            print(f"Preserved existing index in {self.backup_root}", file=sys.stderr)
        if self.index.exists():
            shutil.rmtree(self.index)
        return self

    def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
        if self.keep_benchmark_index:
            return
        if self.index.exists():
            shutil.rmtree(self.index)
        if self.backup_root is not None:
            shutil.copytree(self.backup_root / ".quill", self.index)
            shutil.rmtree(self.backup_root)


def print_report(result: dict[str, Any], output: Path) -> None:
    cold = result["cold_init"]
    print(f"Cold init: {cold['duration_seconds']:.3f}s, "
          f"peak RSS: {cold['peak_rss_mib'] if cold['peak_rss_mib'] is not None else 'n/a'} MiB")
    timings = cold.get("phase_timings_ms", {})
    if timings:
        print("Phases: " + ", ".join(
            f"{phase}={millis}ms" for phase, millis in timings.items()))
    print(f"Index: {result['index']['database_count']} DB, "
          f"{result['index']['size_mib']:.2f} MiB")
    print("\ntool                  samples  p50(ms)  p95(ms)  max(ms)  errors")
    for tool in result["mcp"]["tools"]:
        print(f"{tool['tool']:<21} {tool['samples']:>7}  "
              f"{tool['p50_latency_ms']:>7.2f}  {tool['p95_latency_ms']:>7.2f}  "
              f"{tool['max_latency_ms']:>7.2f}  {tool['errors']:>6}")
    print("\nrequests  total(s)  req/s    p50(ms)  p95(ms)  max(ms)  errors")
    for batch in result["mcp"]["batches"]:
        print(f"{batch['concurrent_requests']:>8}  {batch['elapsed_seconds']:>8.3f}  "
              f"{batch['throughput_requests_per_second']:>7.2f}  "
              f"{batch['p50_latency_ms']:>7.2f}  {batch['p95_latency_ms']:>7.2f}  "
              f"{batch['max_latency_ms']:>7.2f}  {batch['errors']:>6}")
    print(f"\nJSON: {output}")


def main() -> int:
    args = arguments()
    project = args.project.resolve()
    configured_quill = os.path.expanduser(args.quill)
    discovered_quill = shutil.which(configured_quill)
    quill = Path(discovered_quill or configured_quill).resolve()
    if not project.is_dir():
        raise ValueError(f"Project directory does not exist: {project}")
    if not quill.is_file():
        raise ValueError(f"Quill binary does not exist: {quill}")
    if args.samples_per_tool <= 0:
        raise ValueError("--samples-per-tool must be positive")
    concurrency = parse_concurrency(args.concurrency)
    output = (args.output or Path("target/benchmarks") /
              f"quill-{project.name}-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}.json").resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    existing_index = project / ".quill"
    original_project_state = project_metadata(project)
    with IndexSandbox(existing_index, args.keep_benchmark_index):
        command = [str(quill)]
        cold = run_measured(command + ["init", "--project", str(project),
                                     "--index-only", "--timings"], project)
        cold["phase_timings_ms"] = parse_phase_timings(cold["stderr"])
        if cold["exit_code"] != 0:
            sys.stderr.write(cold["stdout"] + cold["stderr"])
            return cold["exit_code"] or 1
        database_count, size_bytes = index_size(project)
        result = {
            "schema_version": 2,
            "measured_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "host": host_metadata(),
            "project": str(project),
            "project_state": original_project_state,
            "quill": {
                "path": str(quill),
                "version": output_of([str(quill), "--version"]),
            },
            "configuration": {
                "concurrency": concurrency,
                "warmup": args.warmup,
                "samples_per_tool": args.samples_per_tool,
                "request_timeout_seconds": args.request_timeout,
                "tools": [name for name, _ in TOOLS],
            },
            "cold_init": cold,
            "index": {
                "database_count": database_count,
                "size_bytes": size_bytes,
                "size_mib": round(size_bytes / 1024 / 1024, 2),
            },
            "mcp": benchmark_mcp(command, project, args.warmup,
                                 args.samples_per_tool, concurrency,
                                 args.request_timeout),
        }
        output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print_report(result, output)
        return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, TimeoutError) as exc:
        print(f"Benchmark failed: {exc}", file=sys.stderr)
        raise SystemExit(2)
