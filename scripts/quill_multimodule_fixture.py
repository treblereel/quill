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


def add_impact_controls(project):
    """Fixed, independent source oracle: two related modules/tests and one negative control."""
    project = Path(project)
    if (project / ".git").exists() or (project / "unrelated").exists() or not (project / "api/pom.xml").is_file():
        raise ValueError("Expected fresh two-module harness fixture")
    pom = project / "pom.xml"
    pom.write_text(pom.read_text().replace("</modules>", "<module>unrelated</module></modules>"))
    (project / "unrelated").mkdir()
    (project / "unrelated/pom.xml").write_text((project / "core/pom.xml").read_text().replace(
        "<artifactId>core</artifactId>", "<artifactId>unrelated</artifactId>"))
    junit = "<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter-api</artifactId>" \
        "<version>5.12.2</version><scope>test</scope></dependency>"
    for module in ("core", "api", "unrelated"):
        module_pom = project / module / "pom.xml"
        content = module_pom.read_text()
        content = content.replace("</dependencies>", junit + "</dependencies>") if "</dependencies>" in content \
            else content.replace("</project>", "<dependencies>" + junit + "</dependencies></project>")
        module_pom.write_text(content)
    main = project / "unrelated/src/main/java/org/example/UnrelatedService.java"
    main.parent.mkdir(parents=True)
    main.write_text("package org.example; public class UnrelatedService { public boolean value() { return true; } }\n")
    for module, test, statement in (
        ("core", "GreetingServiceTest", 'org.junit.jupiter.api.Assertions.assertEquals("Welcome, A", new GreetingService().greet("A"));'),
        ("api", "GreetingEndpointTest", 'org.junit.jupiter.api.Assertions.assertEquals("Welcome, B", new GreetingEndpoint().render("B"));'),
        ("unrelated", "UnrelatedServiceTest", 'org.junit.jupiter.api.Assertions.assertTrue(new UnrelatedService().value());')):
        path = project / module / "src/test/java/org/example" / (test + ".java")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("package org.example; public class " + test + " { @org.junit.jupiter.api.Test "
                        "void checksBehavior() { " + statement + " } }\n")
    return {"review_modules": ["core", "api"],
            "review_tests": ["org.example.GreetingServiceTest", "org.example.GreetingEndpointTest"],
            "excluded_module": "unrelated", "excluded_test": "org.example.UnrelatedServiceTest",
            "origin": "fixed generated source graph and JUnit annotations, not Quill output"}
