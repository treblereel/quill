#!/usr/bin/env python3
"""Generate a deterministic Java fixture and enforce broad Quill performance budgets."""

from __future__ import annotations

import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

from quill_benchmark import benchmark_mcp, parse_phase_timings, run_measured


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", type=Path, required=True)
    parser.add_argument("--classes", type=int, default=400)
    parser.add_argument("--max-index-seconds", type=float, default=15)
    parser.add_argument("--max-rss-mib", type=float, default=768)
    parser.add_argument("--max-burst-seconds", type=float, default=10)
    return parser.parse_args()


def generate_fixture(root: Path, class_count: int) -> None:
    if class_count < 2:
        raise ValueError("--classes must be at least 2")
    source_root = root / "src" / "main" / "java"
    inject = source_root / "jakarta" / "inject" / "Inject.java"
    scoped = source_root / "jakarta" / "enterprise" / "context" / "ApplicationScoped.java"
    inject.parent.mkdir(parents=True)
    scoped.parent.mkdir(parents=True)
    inject.write_text(
        "package jakarta.inject; public @interface Inject {}\n", encoding="utf-8")
    scoped.write_text(
        "package jakarta.enterprise.context; public @interface ApplicationScoped {}\n",
        encoding="utf-8")

    package_root = source_root / "perf" / "fixture"
    package_root.mkdir(parents=True)
    for index in range(class_count):
        next_index = (index + 1) % class_count
        source = (
            "package perf.fixture;\n"
            "@jakarta.enterprise.context.ApplicationScoped\n"
            f"public class Service{index:04d} {{\n"
            "  @jakarta.inject.Inject\n"
            f"  Service{next_index:04d} next;\n"
            f"  public String name() {{ return \"service-{index:04d}\"; }}\n"
            "}\n"
        )
        (package_root / f"Service{index:04d}.java").write_text(source, encoding="utf-8")

    (root / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>perf</groupId><artifactId>fixture</artifactId>"
        "<version>1</version></project>\n",
        encoding="utf-8")
    (root / ".gitignore").write_text("target/\n.quill/\n", encoding="utf-8")

    classes = root / "target" / "classes"
    classes.mkdir(parents=True)
    sources = sorted(source_root.rglob("*.java"))
    arguments_file = root / "target" / "javac.args"
    arguments_file.write_text(
        "-d\n" + str(classes) + "\n" + "\n".join(map(str, sources)) + "\n",
        encoding="utf-8")
    subprocess.run(["javac", f"@{arguments_file}"], cwd=root, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    (root / "target" / "quill-classpath.txt").write_text("", encoding="utf-8")

    subprocess.run(["git", "init", "--quiet"], cwd=root, check=True)
    subprocess.run(["git", "add", "pom.xml", ".gitignore", "src"], cwd=root, check=True)
    subprocess.run([
        "git", "-c", "user.name=Quill CI", "-c", "user.email=quill@example.invalid",
        "commit", "--quiet", "-m", "fixture",
    ], cwd=root, check=True)


def enforce_budgets(result: dict, max_index_seconds: float, max_rss_mib: float,
                    max_burst_seconds: float) -> None:
    cold = result["cold_init"]
    if cold["exit_code"] != 0:
        raise RuntimeError("Quill fixture indexing failed:\n" + cold["stderr"])
    if cold["duration_seconds"] > max_index_seconds:
        raise RuntimeError(
            f"Index time {cold['duration_seconds']}s exceeds {max_index_seconds}s budget")
    if cold["peak_rss_mib"] is not None and cold["peak_rss_mib"] > max_rss_mib:
        raise RuntimeError(
            f"Peak RSS {cold['peak_rss_mib']} MiB exceeds {max_rss_mib} MiB budget")
    if not cold["phase_timings_ms"]:
        raise RuntimeError("Quill did not report phase timings")

    mcp = result["mcp"]
    errors = sum(tool["errors"] for tool in mcp["tools"])
    errors += sum(batch["errors"] for batch in mcp["batches"])
    if errors:
        raise RuntimeError(f"MCP performance smoke test returned {errors} errors")
    slowest = max(batch["elapsed_seconds"] for batch in mcp["batches"])
    if slowest > max_burst_seconds:
        raise RuntimeError(
            f"MCP burst took {slowest}s, exceeding {max_burst_seconds}s budget")


def run_smoke(quill: Path, class_count: int) -> dict:
    if not shutil.which("javac"):
        raise RuntimeError("javac is required for the performance fixture")
    with tempfile.TemporaryDirectory(prefix="quill-perf-") as directory:
        project = Path(directory)
        generate_fixture(project, class_count)
        cold = run_measured([
            str(quill), "init", "--project", str(project), "--index-only", "--timings",
        ], project)
        cold["phase_timings_ms"] = parse_phase_timings(cold["stderr"])
        if cold["exit_code"] != 0:
            return {"cold_init": cold, "mcp": {"tools": [], "batches": []}}
        mcp = benchmark_mcp([str(quill)], project, warmup=1,
                            samples_per_tool=2, concurrency=[32], timeout_seconds=30)
        return {"cold_init": cold, "mcp": mcp}


def main() -> int:
    args = arguments()
    quill = args.quill.resolve()
    result = run_smoke(quill, args.classes)
    enforce_budgets(result, args.max_index_seconds, args.max_rss_mib,
                    args.max_burst_seconds)
    cold = result["cold_init"]
    burst = result["mcp"]["batches"][0]
    print(f"Performance smoke passed: {args.classes} classes, "
          f"index={cold['duration_seconds']}s, peak_rss={cold['peak_rss_mib']} MiB, "
          f"32-request burst={burst['elapsed_seconds']}s")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
