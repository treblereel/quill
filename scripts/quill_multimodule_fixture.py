"""Convert only a harness-owned temporary fixture into a two-module reactor."""
from pathlib import Path
import shutil


def convert(project):
    project = Path(project)
    original = (project / "pom.xml").read_text()
    if "<artifactId>quill-change-loop-fixture</artifactId>" not in original \
            or "<groupId>org.example</groupId>" not in original or (project / "src").is_symlink() \
            or not (project / "src/main/java/org/example/GreetingService.java").is_file():
        raise ValueError("Expected harness fixture, not an arbitrary project")
    if (project / ".git").exists() or (project / "core").exists() or (project / "api").exists():
        raise ValueError("Expected freshly generated fixture before Git initialization")
    for module in ("core", "api"):
        (project / module).mkdir()
        dependencies = "" if module == "core" else """<dependencies><dependency>
<groupId>org.example</groupId><artifactId>core</artifactId><version>1.0-SNAPSHOT</version>
</dependency></dependencies>"""
        (project / module / "pom.xml").write_text("""<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><parent><groupId>org.example</groupId>
<artifactId>quill-change-loop-fixture</artifactId><version>1.0-SNAPSHOT</version></parent>
<artifactId>""" + module + "</artifactId>" + dependencies + "</project>\n")
    (project / "pom.xml").write_text(original.replace("<version>1.0-SNAPSHOT</version>",
        "<version>1.0-SNAPSHOT</version><packaging>pom</packaging><modules><module>core</module><module>api</module></modules>", 1))
    shutil.move(str(project / "src"), str(project / "core/src"))
    source = project / "core/src/main/java/org/example"
    destination = project / "api/src/main/java/org/example"
    destination.mkdir(parents=True)
    for name in ("GreetingController.java", "GreetingEndpoint.java"):
        if (source / name).exists():
            shutil.move(str(source / name), str(destination / name))
    return project / "core/src/main/java/org/example/GreetingService.java"
