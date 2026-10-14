#!/usr/bin/env python3
"""Measure compiler navigation across disposable, independently built Git worktrees.

Uses real Maven/Java or Gradle/Kotlin builds. Never touches a user's repository.
Timings include MCP, preparation, compiler and index matching; RSS includes workers.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import tempfile
import time

from quill_benchmark import process_tree_rss_kib
from quill_workflow_benchmark import WorkflowClient


def sources(root: Path, language: str, first: bool, string_argument: bool = False) -> tuple[str, int, int]:
    kotlin = language == "kotlin"
    relative = Path("src/main") / language / "fixture"
    directory = root / relative
    directory.mkdir(parents=True, exist_ok=True)
    numeric = "Int" if first else "Long"
    java_numeric = "int" if first else "long"
    api = (f"package fixture\nclass Api {{ fun pick(value: {numeric}) = value; fun pick(value: String) = value.length }}\n"
           if kotlin else f"package fixture; public class Api {{ public {java_numeric} pick({java_numeric} value) {{ return value; }} public int pick(String value) {{ return value.length(); }} }}\n")
    (directory / ("Api.kt" if kotlin else "Api.java")).write_text(api, encoding="utf-8")
    argument = '"changed"' if string_argument else "1" if first else "1L"
    line = (f"class Caller {{ fun run() = Api().pick({argument}) }}" if kotlin
            else f"public class Caller {{ public Object run() {{ return new Api().pick({argument}); }} }}")
    name = "Caller.kt" if kotlin else "Caller.java"
    (directory / name).write_text(("package fixture\n" if kotlin else "package fixture;\n") + line + "\n", encoding="utf-8")
    return (relative / name).as_posix(), 2, line.index(".pick") + 2


def run(command: list[str], root: Path) -> None:
    environment = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
    completed = subprocess.run(command, cwd=root, env=environment, stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=240)
    if completed.returncode:
        raise RuntimeError(f"Command failed: {command}\n{completed.stdout[-8000:]}")


def benchmark(quill: str, language: str, samples: int, settle: float) -> dict:
    with tempfile.TemporaryDirectory(prefix="quill-compiler-worktrees-") as directory:
        base = Path(directory)
        a = base / "main space ü"
        b = base / "linked space ü"
        a.mkdir()
        (a / ".gitignore").write_text(".quill/\ntarget/\nbuild/\n.gradle/\n.codex/\n.mcp.json\nCLAUDE.md\nAGENTS.md\n", encoding="utf-8")
        if language == "java":
            (a / "pom.xml").write_text("""<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>compiler-worktree</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release></properties><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin></plugins></build></project>""", encoding="utf-8")
        else:
            (a / "settings.gradle.kts").write_text('rootProject.name = "compiler-worktree"\n', encoding="utf-8")
            (a / "build.gradle.kts").write_text('plugins { kotlin("jvm") version "2.4.21" }\nrepositories { mavenCentral() }\nkotlin { jvmToolchain(21) }\n', encoding="utf-8")
        position = sources(a, language, True)
        run(["git", "init"], a)
        run(["git", "add", "."], a)
        run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", "commit", "-m", "synthetic fixture"], a)
        run(["git", "worktree", "add", "--detach", str(b)], a)
        sources(b, language, False)
        maven = str(Path(__file__).resolve().parents[1] / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
        gradle = shutil.which("gradle")
        if language == "kotlin" and not gradle:
            raise RuntimeError("Gradle is required for the synthetic Kotlin fixture")
        for root in (a, b):
            build = [maven, "-q", "compile"] if language == "java" else [gradle, "classes", "--quiet"]
            if os.name == "nt":
                build = ["cmd", "/d", "/c", *build]
            run(build, root)
            run([quill, "init", "--no-allow-quill-tools", "--project", str(root)], root)
            # Install capture first, then explicitly execute the compiler again.
            run([*build, "--rerun-tasks"] if language == "kotlin" else [*build[:-1], "clean", "compile"], root)
            run([quill, "init", "--index-only", "--project", str(root)], root)
        with WorkflowClient([quill], a, 90) as client:
            def query(root: Path, descriptor: str) -> tuple[dict, float]:
                value, metric = client.call("get_symbol_at_position", {
                    "project": str(root), "path": position[0], "line": position[1], "column": position[2]})
                if (value.get("resolution_basis") != "compiler" or value.get("resolution") != "resolved"
                        or value.get("selected", {}).get("descriptor") != descriptor):
                    raise AssertionError(f"Expected compiler descriptor {descriptor}: {value}")
                return value, metric["latency_ms"]
            left, first_a = query(a, "(I)I")
            right, first_b = query(b, "(J)J")
            assert left["_meta"]["project_id"] != right["_meta"]["project_id"]
            assert left["_meta"]["worktree_id"] != right["_meta"]["worktree_id"]
            warm, changed, reused = [], [], []
            rss_before = process_tree_rss_kib(client.client.process.pid)
            for index in range(samples):
                use_string = index % 2 == 0
                previous_descriptor = "(I)I" if use_string else "(Ljava/lang/String;)I"
                warm.append(query(a, previous_descriptor)[1])
                query(b, "(J)J")
                sources(a, language, True, use_string)
                time.sleep(settle)
                answer, latency = query(a, "(Ljava/lang/String;)I" if use_string else "(I)I")
                changed.append(latency)
                analysis = answer.get("semantic_analysis", {})
                reused.append(analysis.get("analysis_reused", analysis.get("reused")))
                query(b, "(J)J")
            return {"language": language, "samples": samples, "settle_seconds": settle,
                    "first_query_a_ms": first_a, "first_query_b_ms": first_b,
                    "warm_return_a_median_ms": statistics.median(warm),
                    "edited_inactive_a_median_ms": statistics.median(changed),
                    "edited_inactive_a_samples_ms": changed,
                    "edited_inactive_a_analysis_reused": reused,
                    "rss_two_contexts_kib": rss_before,
                    "rss_after_edits_kib": process_tree_rss_kib(client.client.process.pid)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", default=os.environ.get("QUILL_BIN", "quill"))
    parser.add_argument("--language", choices=("java", "kotlin", "both"), default="both")
    parser.add_argument("--samples", type=int, default=5)
    parser.add_argument("--settle", type=float, default=3.0)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.samples < 1 or args.settle < 0:
        parser.error("samples must be positive and settle must be nonnegative")
    quill = shutil.which(args.quill)
    if not quill:
        parser.error("Quill executable not found")
    result = {"fixture": "synthetic compiler worktrees", "results": [
        benchmark(str(Path(quill).resolve()), language, args.samples, args.settle)
        for language in (("java", "kotlin") if args.language == "both" else (args.language,))]}
    rendered = json.dumps(result, indent=2)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
