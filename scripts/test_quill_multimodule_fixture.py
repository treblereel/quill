from pathlib import Path
import tempfile
import unittest

import quill_change_loop_e2e as loop
from quill_multimodule_fixture import convert


class MultimoduleFixtureTest(unittest.TestCase):
    def test_reactor_preserves_core_test_and_moves_api_callers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            loop.write_fixture(root)
            folder = root / "src/main/java/org/example"
            for name in ("GreetingController.java", "GreetingEndpoint.java"):
                (folder / name).write_text(name)
            main = convert(root)
            self.assertTrue(main.is_file())
            self.assertTrue((root / "core/src/test/java/org/example/GreetingServiceCompileProbe.java").is_file())
            self.assertTrue((root / "api/src/main/java/org/example/GreetingEndpoint.java").is_file())
            self.assertIn("<packaging>pom</packaging>", (root / "pom.xml").read_text())
            self.assertIn("<artifactId>core</artifactId>", (root / "api/pom.xml").read_text())
            self.assertFalse((root / "src").exists())
            with self.assertRaises(ValueError):
                convert(root)

    def test_rejects_initialized_repository_before_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            loop.write_fixture(root)
            before = (root / "pom.xml").read_text()
            (root / ".git").mkdir()
            with self.assertRaises(ValueError):
                convert(root)
            self.assertEqual(before, (root / "pom.xml").read_text())

    def test_rejects_non_fixture_pom_without_creating_modules(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").write_text("user project")
            with self.assertRaises(ValueError):
                convert(root)
            self.assertEqual(["pom.xml"], [path.name for path in root.iterdir()])
