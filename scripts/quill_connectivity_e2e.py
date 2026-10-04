#!/usr/bin/env python3
"""Exercise opt-in post-init transport checks with native Quill, without AI clients."""
import argparse
import json
from pathlib import Path
import shutil
import tempfile

import quill_change_loop_e2e as loop


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill"))
    args = parser.parse_args()
    quill = str(args.quill.resolve())
    maven = shutil.which("mvn")
    if not maven:
        raise RuntimeError("System Maven is required for fixture compilation")
    with tempfile.TemporaryDirectory(prefix="quill-connectivity-") as directory:
        workspace = Path(directory).resolve()
        project = workspace / "fixture"
        project.mkdir()
        loop.write_fixture(project)
        loop.run(["git", "init", "-q"], project)
        loop.run([maven, "-q", "test-compile"], project)
        initialized = loop.run([quill, "init", "--probe-mcp"], project)
        assert "transport verified" in initialized.stdout, initialized.stdout
        report = json.loads(loop.run([quill, "doctor", "--probe-mcp", "--json"], project).stdout)
        check = next(check for check in report["checks"] if check["id"] == "mcp_connectivity")
        assert check["status"] == "pass", check
        # Exercise all exposed tool profiles with a real server subprocess.
        config_path = project / ".mcp.json"
        original = json.loads(config_path.read_text())
        for profile in ("core", "router"):
            config = json.loads(json.dumps(original))
            config["mcpServers"]["quill"]["args"] += ["--tools", profile]
            config_path.write_text(json.dumps(config))
            report = json.loads(loop.run([quill, "doctor", "--probe-mcp", "--json"], project).stdout)
            assert next(check for check in report["checks"]
                        if check["id"] == "mcp_connectivity")["status"] == "pass"
        config_path.write_text(json.dumps(original))
        initialized = loop.run([quill, "workspace", "init", "--project", str(workspace),
                                "--probe-mcp"], workspace)
        assert "transport verified" in initialized.stdout, initialized.stdout
        # Negative probe must not undo successful indexing or silently return success.
        config = json.loads(config_path.read_text())
        config["mcpServers"]["quill"]["command"] = str(project / "missing-launcher")
        config_path.write_text(json.dumps(config))
        failed = loop.run_unchecked([quill, "doctor", "--probe-mcp", "--json"], project)
        assert failed.returncode != 0
        report = json.loads(failed.stdout)
        assert next(check for check in report["checks"]
                    if check["id"] == "mcp_connectivity")["status"] == "error"
        assert (project / ".quill").is_dir()
        assert not (project / "target/surefire-reports").exists()
        assert not (project / "target/failsafe-reports").exists()
    print("PASS: project init, doctor, full/core/router, workspace init, broken launcher; no AI clients")


if __name__ == "__main__":
    main()
