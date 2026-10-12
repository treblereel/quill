#!/usr/bin/env python3
"""Test K2 runtime preparation and semantic analysis through the native/JVM distribution."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import statistics
import subprocess
import shutil
import tempfile
import time
import os
import sys
import xml.etree.ElementTree as ET


def assert_clean_shutdown(client) -> dict:
    """On macOS, prove EOF closes MCP and its existing worker without forced termination."""
    process = client.client.process
    listing = subprocess.run(["/bin/ps", "-axo", "pid=,ppid="], capture_output=True,
                             text=True, timeout=5, check=True)
    parents = {int(pid): int(parent) for pid, parent in
               (line.split() for line in listing.stdout.splitlines() if line.strip())}
    descendants = {pid for pid, parent in parents.items() if parent == process.pid}
    while True:
        expanded = descendants | {pid for pid, parent in parents.items() if parent in descendants}
        if expanded == descendants:
            break
        descendants = expanded
    assert descendants, "Expected a live external K2 worker before shutdown"
    assert process.stdin is not None
    process.stdin.close()
    started = time.monotonic()
    assert process.wait(timeout=5) == 0, "MCP did not exit cleanly on EOF"

    def alive(pid):
        try:
            os.kill(pid, 0)
            return True
        except ProcessLookupError:
            return False

    deadline = time.monotonic() + 5
    while any(alive(pid) for pid in descendants) and time.monotonic() < deadline:
        time.sleep(.02)
    assert not any(alive(pid) for pid in descendants), "K2 worker survived MCP EOF"
    return {"mcp_exit_code": process.returncode, "worker_processes": len(descendants),
            "forced_termination": False, "elapsed_ms": (time.monotonic() - started) * 1000}


def run(command: list[str], request: dict | None = None, *, timeout: float = 35) -> tuple[subprocess.CompletedProcess, float]:
    started = time.perf_counter()
    result = subprocess.run(command, input=json.dumps(request) if request is not None else None,
                            text=True, capture_output=True, timeout=timeout, check=True)
    return result, time.perf_counter() - started


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill.exe" if os.name == "nt" else "quill-app/target/quill"))
    parser.add_argument("--stdlib", type=Path, help="Project Kotlin stdlib JAR (defaults to Maven cache)")
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--java-jar", type=Path, help="Run the same checks against the JVM distribution")
    parser.add_argument("--worker-java-home", type=Path, help="JAVA_HOME for the external worker (e.g. JDK 17)")
    parser.add_argument("--agent-output", type=Path, help="Collect GraalVM metadata with --java-jar")
    parser.add_argument("--fixture", type=Path, help="Compiled tests/kotlin-maven fixture for native MCP checks")
    args = parser.parse_args()
    import atexit
    runtime_cache = Path(tempfile.mkdtemp(prefix="quill K2 cache "))
    atexit.register(shutil.rmtree, runtime_cache, ignore_errors=True)
    if args.agent_output and not args.java_jar:
        parser.error("--agent-output requires --java-jar")
    binary = (args.java_jar or args.quill).resolve(strict=True)
    command = [str(binary)]
    if args.java_jar:
        command = [str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))]
        if args.agent_output:
            command += [f"-agentlib:native-image-agent=config-merge-dir={args.agent_output.resolve()}"]
        command += ["-Xmx512m", "-jar", str(binary)]
        command.insert(command.index("-jar"), f"-Dquill.kotlin.cache={runtime_cache}")
    else:
        command += [f"-Dquill.kotlin.cache={runtime_cache}"]
    if args.worker_java_home:
        os.environ["JAVA_HOME"] = str(args.worker_java_home.resolve(strict=True))
    pom = ET.parse(Path(__file__).resolve().parents[1] / "pom.xml")
    version = pom.findtext("{*}properties/{*}kotlin.version")
    stdlib = (args.stdlib or Path.home() / f".m2/repository/org/jetbrains/kotlin/kotlin-stdlib/{version}/kotlin-stdlib-{version}.jar").resolve(strict=True)
    cases = [
        ("int overload", 'fun pick(x: Int) = x\nfun pick(x: String) = x\nfun run() = pick(1)', "pick(", {"status": "resolved", "descriptor": "(I)I"}),
        ("string overload", 'fun pick(x: Int) = x\nfun pick(x: String) = x\nfun run() = pick("s")', "pick(", {"status": "resolved", "descriptor": "(Ljava/lang/String;)Ljava/lang/String;"}),
        ("smart cast", 'class Receiver { fun pick() = 1 }\nfun run(x: Any) { val ok = x is Receiver; if (ok) x.pick() }', "pick()", {"status": "resolved", "owner": "probe.Receiver"}),
        ("context parameter", 'context(prefix: String) fun pick(x: Int) = prefix + x\nfun run() = with("s") { pick(1) }', "pick(", {"status": "resolved", "descriptor": "(Ljava/lang/String;I)Ljava/lang/String;"}),
        ("local shadow", 'fun pick() = 1\nfun run() { val pick = { 2 }; pick() }', "pick()", {"status": "local"}),
        ("generic erasure", 'fun <T> pick(x: T): T = x\nfun run() = pick("s")', "pick(", {"status": "resolved", "descriptor": "(Ljava/lang/Object;)Ljava/lang/Object;"}),
        ("nullable boxing", 'fun pick(x: Int?): Int? = x\nfun run() = pick(null)', "pick(", {"status": "resolved", "descriptor": "(Ljava/lang/Integer;)Ljava/lang/Integer;"}),
        ("array boxing", 'fun pick(x: Array<Int>) = x\nfun run() = pick(arrayOf(1))', "pick(", {"status": "resolved", "descriptor": "([Ljava/lang/Integer;)[Ljava/lang/Integer;"}),
        ("suspend", 'suspend fun pick(x: Int): String = x.toString()\nsuspend fun run() = pick(1)', "pick(", {"status": "resolved", "descriptor": "(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;"}),
        ("binary stdlib", 'fun run() = listOf(1)', "listOf(", {"status": "resolved", "descriptor": "(Ljava/lang/Object;)Ljava/util/List;"}),
        ("JDK symbols", 'fun pick(x: java.util.ArrayList<String>) = x.size\nfun run() = pick(java.util.ArrayList<String>())', "pick(", {"status": "resolved", "descriptor": "(Ljava/util/ArrayList;)I"}),
        ("Java source symbols", 'fun run() = JavaReceiver().pick(1)', "pick(", {"status": "resolved", "owner": "probe.JavaReceiver", "descriptor": "(I)I"}),
        ("constructor", 'class Receiver(x: Int)\nfun run() = Receiver(1)', "Receiver(", {"status": "resolved", "kind": "CONSTRUCTOR", "descriptor": "(I)V"}),
        ("nested type", 'class Outer { class Nested }\nfun run(x: Outer.Nested) = x', "Nested", {"status": "resolved", "kind": "CLASS", "owner": "probe.Outer$Nested"}),
        ("property", 'class Receiver { val name = "s" }\nfun run(r: Receiver) = r.name', "name", {"status": "resolved", "kind": "PROPERTY", "owner": "probe.Receiver"}),
        ("JVM annotation", '@JvmName("renamed") fun pick(x: Int) = x\nfun run() = pick(1)', "pick(", {"status": "resolved", "name": "renamed", "descriptor": "(I)I"}),
        ("object receiver", 'object Receiver { fun pick(x: Int) = x }\nfun run() = Receiver.pick(1)', "pick(", {"status": "resolved", "owner": "probe.Receiver", "descriptor": "(I)I"}),
        ("type mismatch", 'fun pick(x: Int) = x\nfun run() = pick("bad")', "pick(", {"status": "analysis_error"}),
        ("incomplete source", 'fun pick() = 1\nfun run() { pick()', "pick()", {"status": "analysis_error"}),
        ("literal position", 'fun pick(x: String) = x\nfun run() = pick("literal")', "literal", {"status": "unresolved"}),
        ("standalone script", 'fun pick(x: Int) = x\nval result = pick(1)\nerror("must never evaluate")', "pick(", {"status": "resolved", "descriptor": "(I)I"}),
        ("project language settings", 'fun pick(x: Int) = x\nfun run() = pick(1)', "pick(", {"status": "resolved", "language_version": "2.3"}),
        ("disabled project plugin", 'fun pick(x: Int) = x\nfun run() = pick(1)', "pick(", {"status": "compiler_plugins_disabled"}),
        ("all-open project plugin", 'annotation class Open\n@Open class Base { fun pick() = 1 }\nclass Derived: Base()\nfun run() = Derived().pick()', "pick()", {"status": "resolved", "descriptor": "()I", "owner": "probe.Base"}),
        ("nonsemantic compiler flag", 'fun pick(x: Int) = x\nfun run() = pick(1)', "pick(", {"status": "unavailable"}),
    ]
    report = {"binary_bytes": binary.stat().st_size, "cases": []}
    with tempfile.TemporaryDirectory(prefix="quill native K2 ") as directory:
        fixture = args.fixture or Path(__file__).resolve().parents[1] / "tests/kotlin-maven"
        project = Path(directory) / "initialization"
        project.mkdir()
        for path in ("src", "target/classes"):
            shutil.copytree(fixture / path, project / path)
        shutil.copyfile(fixture / "pom.xml", project / "pom.xml")
        before = {p.relative_to(project): p.read_bytes() for p in project.rglob("*") if p.is_file()}
        # Initialization includes cold Maven resolution or Gradle startup; query
        # latency is checked separately and retains the shorter deadline.
        initialization, elapsed = run(command + ["init", "--no-allow-quill-tools", "--project", str(project)], timeout=180)
        assert "runtime ready" in initialization.stderr, initialization.stderr
        report["runtime_preparation_seconds"] = elapsed
        for path, data in before.items():
            assert (project / path).read_bytes() == data, path
        # Gradle uses the same runtime even when its build script cannot be configured.
        (project / "pom.xml").unlink()
        (project / "build.gradle.kts").write_text('// compiler resolution must not depend on this script\nthrow GradleException("configuration unavailable")\n')
        shutil.copytree(project / "target/classes", project / "build/classes/kotlin/main")
        gradle_init, elapsed = run(command + ["init", "--no-allow-quill-tools", "--project", str(project)], timeout=180)
        assert "runtime ready" in gradle_init.stderr, gradle_init.stderr
        report["gradle_runtime_preparation_seconds"] = elapsed
        file = Path(directory) / "Probe.kt"
        java_root = Path(directory) / "java"
        java_file = java_root / "probe/JavaReceiver.java"
        java_file.parent.mkdir(parents=True)
        java_file.write_text("package probe; public class JavaReceiver { public int pick(int x) { return x; } }")
        for name, body, token, expected in cases:
            file = Path(directory) / ("Probe.kts" if name == "standalone script" else "Probe.kt")
            source = "package probe\n" + body
            file.write_text(source, encoding="utf-8")
            request = {"file": str(file), "sources": [str(file)], "classpath": [str(stdlib)],
                       "java_roots": [str(java_root)],
                       "offset": source.rfind(token)}
            if name == "project language settings":
                request.update(languageVersion="2.3", apiVersion="2.3", jvmTarget="17")
            if name == "disabled project plugin":
                request["plugin_classpath"] = [str(Path(directory) / "missing-plugin.jar")]
            if name == "all-open project plugin":
                plugin = (Path.home() / f".m2/repository/org/jetbrains/kotlin/kotlin-allopen-compiler-plugin-embeddable/{version}/kotlin-allopen-compiler-plugin-embeddable-{version}.jar").resolve(strict=True)
                request.update(allow_compiler_plugins=True, compilerVersion=version,
                               plugin_classpath=[str(plugin)], plugin_options=["plugin:org.jetbrains.kotlin.allopen:annotation=probe.Open"])
            if name == "nonsemantic compiler flag":
                request["compiler_arguments"] = ["-d", str(Path(directory) / "must-not-exist")]
            worker = command + ([] if args.java_jar else ["-Xmx512m"]) + ["--kotlin-semantic-worker"]
            result, elapsed = run(worker, request)
            actual = json.loads(result.stdout)
            assert actual.get("backend") == "kotlin_k2_fir", actual
            for key, value in expected.items():
                assert actual.get(key) == value, (name, expected, actual, result.stderr)
            report["cases"].append({"name": name, "seconds": elapsed, "result": actual})
    report["startup_version_seconds"] = [run(command + ["--version"])[1] for _ in range(6)]
    report["startup_warm_median_seconds"] = statistics.median(report["startup_version_seconds"][1:])
    if os.name != "nt":
        import resource
        rss = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
        report["max_child_rss_bytes"] = rss if sys.platform == "darwin" else rss * 1024
    assert "kotlin-semantic-worker" not in run(command + ["--help"])[0].stdout
    if args.baseline:
        baseline = args.baseline.resolve(strict=True)
        report["baseline_bytes"] = baseline.stat().st_size
        report["growth_bytes"] = report["binary_bytes"] - report["baseline_bytes"]
        report["growth_percent"] = report["growth_bytes"] / report["baseline_bytes"] * 100
        times = [run([str(baseline), "--version"])[1] for _ in range(6)]
        report["baseline_startup_version_seconds"] = times
        report["baseline_startup_warm_median_seconds"] = statistics.median(times[1:])
    if args.fixture:
        from quill_workflow_benchmark import WorkflowClient
        with tempfile.TemporaryDirectory(prefix="quill-kotlin-mcp-") as directory:
            project = Path(directory).resolve()
            for path in ("src", "target/classes"):
                shutil.copytree(args.fixture / path, project / path)
            shutil.copyfile(args.fixture / "pom.xml", project / "pom.xml")
            captured = project / "target/quill-classpath.txt"
            captured.write_text(str(stdlib))
            caller = project / "src/main/kotlin/NativeProbe.kt"
            caller.write_text("package org.treblereel.mcp.fixture.kotlin\nfun nativeProbe() = typedNavigation(1)\n")
            subprocess.run(command + ["init", "--index-only", "--project", str(project)],
                           capture_output=True, text=True, timeout=90, check=True)
            report["mcp"] = {}
            for mode in ("metadata_fallback", "prepared_k2"):
                if mode == "metadata_fallback":
                    captured.unlink()
                else:
                    captured.write_text(str(stdlib))
                with WorkflowClient(command, project, 60) as client:
                    result, metrics = client.call("get_symbol_at_position", {
                        "path": "src/main/kotlin/NativeProbe.kt", "line": 2, "column": 21})
                    report["mcp"][mode] = {"result": result, "metrics": metrics}
                    if mode == "prepared_k2":
                        samples = []
                        previous_generation = result["semantic_analysis"]["analysis_generation"]
                        for i in range(20):
                            argument = '"changed"' if i % 2 else str(i)
                            caller.write_text("package org.treblereel.mcp.fixture.kotlin\n"
                                              f"fun nativeProbe() = typedNavigation({argument})\n")
                            warm, timing = client.call("get_symbol_at_position", {
                                "path": "src/main/kotlin/NativeProbe.kt", "line": 2, "column": 21})
                            expected = "(Ljava/lang/String;)Ljava/lang/String;" if i % 2 else "(I)I"
                            assert warm["selected"]["jvm_descriptor"] == expected, warm
                            assert warm["semantic_analysis"]["status"] == "resolved", warm
                            # A background warmup may have completed before this foreground query.
                            current_generation = warm["semantic_analysis"]["analysis_generation"]
                            assert current_generation > previous_generation, warm
                            previous_generation = current_generation
                            samples.append(timing["latency_ms"])
                        report["mcp"][mode]["warm_fresh_source_latency_ms"] = samples
                        report["mcp"][mode]["warm_fresh_source_median_ms"] = statistics.median(samples)
                        report["mcp"][mode]["warm_fresh_source_p95_ms"] = sorted(samples)[18]
                        generation = warm["semantic_analysis"]["analysis_generation"]
                        unchanged = []
                        for _ in range(20):
                            warm, timing = client.call("get_symbol_at_position", {
                                "path": "src/main/kotlin/NativeProbe.kt", "line": 2, "column": 21})
                            assert warm["selected"]["jvm_descriptor"] == "(Ljava/lang/String;)Ljava/lang/String;", warm
                            assert warm["semantic_analysis"]["analysis_reused"] is True, warm
                            assert warm["semantic_analysis"]["analysis_generation"] == generation, warm
                            unchanged.append(timing["latency_ms"])
                        report["mcp"][mode]["warm_unchanged_source_latency_ms"] = unchanged
                        report["mcp"][mode]["warm_unchanged_source_median_ms"] = statistics.median(unchanged)
                        caller.write_text("package org.treblereel.mcp.fixture.kotlin\n"
                                          "fun nativeProbe() = typedNavigation(42)\n")
                        # Allow filesystem delivery (polling on some platforms), debounce and
                        # frontend work to finish without sending any intervening MCP query.
                        time.sleep(1)
                        ready, timing = client.call("get_symbol_at_position", {
                            "path": "src/main/kotlin/NativeProbe.kt", "line": 2, "column": 21})
                        assert ready["selected"]["jvm_descriptor"] == "(I)I", ready
                        assert ready["semantic_analysis"]["analysis_reused"] is True, ready
                        expected_watcher = ("directory_watcher_fsevents" if sys.platform == "darwin"
                                            else "directory_watcher_watch_service")
                        assert ready["semantic_analysis"]["input_detection"] == expected_watcher, ready
                        assert ready["semantic_analysis"]["analysis_generation"] > generation, ready
                        report["mcp"][mode]["background_warmed_query"] = {
                            "result": ready, "metrics": timing, "idle_allowance_seconds": 1}
                        script = project / "src/main/kotlin/NativeScript.kts"
                        script.write_text("package org.treblereel.mcp.fixture.kotlin\n"
                                          "val result = typedNavigation(1)\nerror(\"must never evaluate\")\n")
                        scripted, timing = client.call("get_symbol_at_position", {
                            "path": "src/main/kotlin/NativeScript.kts", "line": 2, "column": 16})
                        assert scripted["semantic_analysis"]["status"] == "resolved", scripted
                        assert scripted["selected"]["jvm_descriptor"] == "(I)I", scripted
                        report["mcp"][mode]["standalone_script"] = {"result": scripted, "metrics": timing}
                        script.unlink()
                        if sys.platform == "darwin":
                            report["mcp"][mode]["eof_shutdown"] = assert_clean_shutdown(client)
            # A fresh MCP session warms the sole indexed Kotlin main module without
            # any semantic query. This checks reuse after an allowance, not readiness time.
            with WorkflowClient(command, project, 60) as client:
                time.sleep(5)
                first, timing = client.call("get_symbol_at_position", {
                    "path": "src/main/kotlin/NativeProbe.kt", "line": 2, "column": 21})
                assert first["confidence"] == "high", first
                assert first["selected"]["jvm_descriptor"] == "(I)I", first
                assert first["semantic_analysis"]["analysis_reused"] is True, first
                report["mcp"]["startup_warmed_first_query"] = {"result": first, "metrics": timing,
                                                               "readiness_allowance_seconds": 5}
                if sys.platform == "darwin":
                    report["mcp"]["startup_warmed_first_query"]["eof_shutdown"] = assert_clean_shutdown(client)
            semantic = report["mcp"]["prepared_k2"]["result"]
            assert semantic["confidence"] == "high", semantic
            assert semantic["semantic_analysis"]["status"] == "resolved", semantic
            assert semantic["selected"]["jvm_descriptor"] == "(I)I", semantic
            assert report["mcp"]["metadata_fallback"]["result"]["confidence"] != "high"
    payload = json.dumps(report, indent=2)
    if args.output:
        args.output.write_text(payload + "\n", encoding="utf-8")
    print(payload)


if __name__ == "__main__":
    main()
