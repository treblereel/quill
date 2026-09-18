#!/usr/bin/env python3
"""Generate a deterministic Java fixture and enforce broad Quill performance budgets."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

from quill_benchmark import (benchmark_catalog, benchmark_mcp, parse_phase_timings,
                             run_measured)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", type=Path, required=True)
    parser.add_argument("--classes", type=int, default=400)
    parser.add_argument("--dependencies", type=int, default=32)
    parser.add_argument("--max-index-seconds", type=float, default=15)
    parser.add_argument("--max-rss-mib", type=float, default=768)
    parser.add_argument("--max-burst-seconds", type=float, default=10)
    parser.add_argument("--max-catalog-bytes", type=int, default=49152)
    parser.add_argument("--max-router-catalog-bytes", type=int, default=4096)
    parser.add_argument("--max-router-ratio", type=float, default=0.20)
    return parser.parse_args()


def generate_fixture(root: Path, class_count: int, dependency_count: int = 32) -> None:
    if class_count < 2:
        raise ValueError("--classes must be at least 2")
    if dependency_count < 1:
        raise ValueError("--dependencies must be at least 1")

    dependency_sources = root / "target" / "dependency-sources" / "perf" / "dependency"
    dependency_classes = root / "target" / "dependency-classes"
    dependency_jars = root / "target" / "dependency-repository"
    dependency_sources.mkdir(parents=True)
    dependency_classes.mkdir(parents=True)
    dependency_jars.mkdir(parents=True)
    for index in range(dependency_count):
        (dependency_sources / f"External{index:04d}.java").write_text(
            "package perf.dependency; "
            f"public class External{index:04d} {{}}\n",
            encoding="utf-8")
    subprocess.run([
        "javac", "-d", str(dependency_classes),
        *map(str, sorted(dependency_sources.glob("*.java"))),
    ], cwd=root, check=True, stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE, text=True)

    jars = []
    for index in range(dependency_count):
        relative_class = Path("perf/dependency") / f"External{index:04d}.class"
        artifact = f"dependency-{index:04d}"
        artifact_dir = dependency_jars / "perf" / "dependency" / artifact / "1"
        artifact_dir.mkdir(parents=True, exist_ok=True)
        jar = artifact_dir / f"{artifact}-1.jar"
        info = zipfile.ZipInfo(relative_class.as_posix(), (2020, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr(info, (dependency_classes / relative_class).read_bytes())
        (artifact_dir / f"{artifact}-1.pom").write_text(
            "<project><modelVersion>4.0.0</modelVersion>"
            f"<groupId>perf.dependency</groupId><artifactId>{artifact}</artifactId>"
            "<version>1</version></project>\n", encoding="utf-8")
        jars.append(jar)

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
            f"  private perf.dependency.External{index % dependency_count:04d} external;\n"
            "  @jakarta.inject.Inject\n"
            f"  Service{next_index:04d} next;\n"
            f"  public String name() {{ return \"service-{index:04d}\"; }}\n"
            "}\n"
        )
        (package_root / f"Service{index:04d}.java").write_text(source, encoding="utf-8")

    dependency_xml = "".join(
        "<dependency><groupId>perf.dependency</groupId>"
        f"<artifactId>dependency-{index:04d}</artifactId><version>1</version>"
        "</dependency>" for index in range(dependency_count))
    (root / "pom.xml").write_text(
        "<project><modelVersion>4.0.0</modelVersion>"
        "<groupId>perf</groupId><artifactId>fixture</artifactId>"
        f"<version>1</version><repositories><repository><id>fixture</id><url>"
        f"{dependency_jars.as_uri()}</url></repository></repositories>"
        f"<dependencies>{dependency_xml}</dependencies></project>\n",
        encoding="utf-8")
    (root / ".gitignore").write_text("target/\n.quill/\n", encoding="utf-8")

    classes = root / "target" / "classes"
    classes.mkdir(parents=True)
    sources = sorted(source_root.rglob("*.java"))
    arguments_file = root / "target" / "javac.args"
    arguments_file.write_text(
        "-classpath\n" + os.pathsep.join(map(str, jars)) + "\n"
        + "-d\n" + str(classes) + "\n" + "\n".join(map(str, sources)) + "\n",
        encoding="utf-8")
    subprocess.run(["javac", f"@{arguments_file}"], cwd=root, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    (root / "target" / "quill-classpath.txt").write_text(
        os.pathsep.join(map(str, jars)), encoding="utf-8")

    subprocess.run(["git", "init", "--quiet"], cwd=root, check=True)
    subprocess.run(["git", "add", "pom.xml", ".gitignore", "src"], cwd=root, check=True)
    subprocess.run([
        "git", "-c", "user.name=Quill CI", "-c", "user.email=quill@example.invalid",
        "commit", "--quiet", "-m", "fixture",
    ], cwd=root, check=True)


def enforce_budgets(result: dict, max_index_seconds: float, max_rss_mib: float,
                    max_burst_seconds: float, max_catalog_bytes: int = 49152,
                    max_router_catalog_bytes: int = 4096,
                    max_router_ratio: float = 0.20) -> None:
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
    if "dependency_jar_index" not in cold["phase_timings_ms"] \
            or " dependency JARs..." not in cold["stderr"]:
        raise RuntimeError("Dependency cache-miss run did not index dependency JARs")

    cache_hit = result["cache_hit"]
    if cache_hit["exit_code"] != 0:
        raise RuntimeError("Quill dependency cache-hit indexing failed:\n"
                           + cache_hit["stderr"])
    if cache_hit["duration_seconds"] > max_index_seconds:
        raise RuntimeError(
            f"Cache-hit index time {cache_hit['duration_seconds']}s exceeds "
            f"{max_index_seconds}s budget")
    if cache_hit["peak_rss_mib"] is not None and cache_hit["peak_rss_mib"] > max_rss_mib:
        raise RuntimeError(
            f"Cache-hit peak RSS {cache_hit['peak_rss_mib']} MiB exceeds "
            f"{max_rss_mib} MiB budget")
    hit_timings = cache_hit["phase_timings_ms"]
    if "dependency_cache_read" not in hit_timings \
            or "[quill] Reusing dependency index for " not in cache_hit["stderr"]:
        raise RuntimeError("Dependency cache-hit run did not read the cache")
    if hit_timings.get("dependency_jar_index", 0) != 0:
        raise RuntimeError("Dependency cache-hit run unexpectedly re-indexed JARs")

    mcp = result["mcp"]
    errors = sum(tool["errors"] for tool in mcp["tools"])
    errors += sum(batch["errors"] for batch in mcp["batches"])
    if errors:
        raise RuntimeError(f"MCP performance smoke test returned {errors} errors")
    slowest = max(batch["elapsed_seconds"] for batch in mcp["batches"])
    if slowest > max_burst_seconds:
        raise RuntimeError(
            f"MCP burst took {slowest}s, exceeding {max_burst_seconds}s budget")
    catalog_bytes = mcp.get("tool_catalog_bytes")
    if not isinstance(catalog_bytes, int) or catalog_bytes <= 0:
        raise RuntimeError("Full MCP catalog measurement is missing")
    if catalog_bytes > max_catalog_bytes:
        raise RuntimeError(
            f"Full MCP catalog is {catalog_bytes} bytes, exceeding "
            f"{max_catalog_bytes} byte budget")
    router = result.get("router_catalog", {})
    router_count = router.get("tool_catalog_count")
    router_bytes = router.get("tool_catalog_bytes")
    if router_count != 3:
        raise RuntimeError(f"Router profile must expose exactly 3 tools, got {router_count}")
    if not isinstance(router_bytes, int) or router_bytes <= 0:
        raise RuntimeError("Router MCP catalog measurement is missing")
    if router_bytes > max_router_catalog_bytes:
        raise RuntimeError(
            f"Router catalog is {router_bytes} bytes, exceeding "
            f"{max_router_catalog_bytes} byte budget")
    ratio = router_bytes / catalog_bytes
    if ratio > max_router_ratio:
        raise RuntimeError(
            f"Router/full catalog ratio {ratio:.3f} exceeds {max_router_ratio:.3f}")


def run_smoke(quill: Path, class_count: int, dependency_count: int) -> dict:
    if not shutil.which("javac"):
        raise RuntimeError("javac is required for the performance fixture")
    with tempfile.TemporaryDirectory(prefix="quill-perf-") as directory:
        project = Path(directory)
        generate_fixture(project, class_count, dependency_count)
        cold = run_measured([
            str(quill), "init", "--project", str(project), "--index-only", "--timings",
        ], project)
        cold["phase_timings_ms"] = parse_phase_timings(cold["stderr"])
        if cold["exit_code"] != 0:
            return {"cold_init": cold, "cache_hit": {},
                    "mcp": {"tools": [], "batches": []}}
        cache_hit = run_measured([
            str(quill), "init", "--project", str(project), "--index-only", "--timings",
        ], project)
        cache_hit["phase_timings_ms"] = parse_phase_timings(cache_hit["stderr"])
        mcp = benchmark_mcp([str(quill)], project, warmup=1,
                            samples_per_tool=2, concurrency=[32], timeout_seconds=30)
        router_catalog = benchmark_catalog(
            [str(quill), "--tools", "router"], project, timeout_seconds=30)
        return {"cold_init": cold, "cache_hit": cache_hit, "mcp": mcp,
                "router_catalog": router_catalog}


def main() -> int:
    args = arguments()
    quill = args.quill.resolve()
    result = run_smoke(quill, args.classes, args.dependencies)
    enforce_budgets(result, args.max_index_seconds, args.max_rss_mib,
                    args.max_burst_seconds, args.max_catalog_bytes,
                    args.max_router_catalog_bytes, args.max_router_ratio)
    cold = result["cold_init"]
    cache_hit = result["cache_hit"]
    burst = result["mcp"]["batches"][0]
    print(f"Performance smoke passed: {args.classes} classes, "
          f"index={cold['duration_seconds']}s, peak_rss={cold['peak_rss_mib']} MiB, "
          f"cache_hit={cache_hit['duration_seconds']}s, "
          f"32-request burst={burst['elapsed_seconds']}s, "
          f"catalog={result['mcp']['tool_catalog_bytes']} bytes, "
          f"router={result['router_catalog']['tool_catalog_bytes']} bytes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
