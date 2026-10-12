#!/usr/bin/env python3
"""Prove native MCP/K2 shutdown on macOS with a changed-source query pending at EOF."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

from quill_kotlin_native_e2e import assert_clean_shutdown
from quill_workflow_benchmark import WorkflowClient


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", type=Path, default=root / "quill-app/target/quill")
    parser.add_argument("--fixture", type=Path, default=root / "tests/kotlin-maven")
    parser.add_argument("--runtime-cache", type=Path, required=True, help="Already prepared K2 cache; used offline")
    parser.add_argument("--worker-java-home", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if sys.platform != "darwin":
        parser.error("This shutdown probe uses macOS process inspection")
    version = ET.parse(root / "pom.xml").findtext("{*}properties/{*}kotlin.version")
    stdlib = (Path.home() / f".m2/repository/org/jetbrains/kotlin/kotlin-stdlib/{version}/kotlin-stdlib-{version}.jar").resolve(strict=True)
    command = [str(args.quill.resolve(strict=True)), f"-Dquill.kotlin.cache={args.runtime_cache.resolve(strict=True)}",
               "-Dquill.kotlin.offline=true"]
    reports = []
    for home in args.worker_java_home:
        os.environ["JAVA_HOME"] = str(home.resolve(strict=True))
        with tempfile.TemporaryDirectory(prefix="quill-pending-eof-") as directory:
            project = Path(directory).resolve()
            for path in ("src", "target/classes"):
                shutil.copytree(args.fixture / path, project / path)
            shutil.copyfile(args.fixture / "pom.xml", project / "pom.xml")
            (project / "target/quill-classpath.txt").write_text(str(stdlib))
            caller = project / "src/main/kotlin/NativeProbe.kt"
            caller.write_text("package org.treblereel.mcp.fixture.kotlin\nfun nativeProbe() = typedNavigation(1)\n")
            subprocess.run(command + ["init", "--index-only", "--project", str(project)],
                           capture_output=True, text=True, check=True, timeout=90)
            with WorkflowClient(command, project, 60) as client:
                arguments = {"path": "src/main/kotlin/NativeProbe.kt", "line": 2, "column": 21}
                first, _ = client.call("get_symbol_at_position", arguments)
                assert first["semantic_analysis"]["status"] == "resolved", first
                for i in range(500):
                    (project / f"src/main/kotlin/ShutdownLoad{i}.kt").write_text(
                        f"package shutdownload\nfun f{i}() = {i}\n")
                caller.write_text('package org.treblereel.mcp.fixture.kotlin\nfun nativeProbe() = typedNavigation("changed")\n')
                client.client.send(client.next_id, "tools/call", {
                    "name": "get_symbol_at_position", "arguments": arguments})
                client.client.flush()
                assert client.client.responses.empty(), "Request already completed before the EOF probe"
                report = assert_clean_shutdown(client)
                report.update(worker_java_home=str(home), additional_sources=500,
                              changed_query_sent_without_waiting=True)
                reports.append(report)
    args.output.write_text(json.dumps(reports, indent=2) + "\n")
    print(json.dumps(reports, indent=2))


if __name__ == "__main__":
    main()
