package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.ProjectIdentity;
import org.treblereel.mcp.mcp.ProjectRegistry;
import org.treblereel.mcp.mcp.QuillTools;

@Tag("e2e")
class WorktreeIsolationIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPOSITORY = Path.of(System.getProperty("user.dir")).getParent().getParent();
    @TempDir Path temp;

    @Test void mavenJavaBuildsAndConcurrentQueriesStayInTheirOwnCheckout() throws Exception { verify(false); }
    @Test void gradleKotlinBuildsAndConcurrentQueriesStayInTheirOwnCheckout() throws Exception { verify(true); }

    private void verify(boolean kotlin) throws Exception {
        Path main = Files.createDirectory(temp.resolve("main space ü"));
        Files.writeString(main.resolve(".gitignore"), ".quill/\ntarget/\nbuild/\n.gradle/\n");
        if (kotlin) {
            Files.writeString(main.resolve("settings.gradle.kts"), "rootProject.name = \"worktree-fixture\"\n");
            Files.writeString(main.resolve("build.gradle.kts"), """
                    plugins { kotlin("jvm") version "2.4.20" }
                    repositories { mavenCentral() }
                    kotlin { jvmToolchain(21) }
                    """);
        } else {
            Files.writeString(main.resolve("pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion>
                    <groupId>fixture</groupId><artifactId>worktree</artifactId><version>1</version>
                    <properties><maven.compiler.release>21</maven.compiler.release></properties>
                    <build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin></plugins></build>
                    </project>
                    """);
        }
        Files.writeString(main.resolve(".mcp.json"), JSON.writeValueAsString(java.util.Map.of(
                "mcpServers", java.util.Map.of("quill", java.util.Map.of("command", "quill",
                        "args", List.of("--mcp", "--project", main.toString()))))));
        writeSource(main, kotlin, "first");
        git(main, "init"); git(main, "add", ".");
        git(main, "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", "commit", "-m", "fixture");
        Path linked = temp.resolve("linked space ü");
        git(main, "worktree", "add", "--detach", linked.toString());
        writeSource(linked, kotlin, "second");
        for (Path project : List.of(main, linked)) {
            assertNotEquals(BuildIntegrationInstaller.Result.FAILED, BuildIntegrationInstaller.install(project));
            List<String> build = kotlin ? BuildSystem.GRADLE.command(project, "classes", "--quiet")
                    : List.of(REPOSITORY.resolve(BuildSystem.isWindows() ? "mvnw.cmd" : "mvnw").toString(), "-q", "-f", project.resolve("pom.xml").toString(), "compile");
            if (!kotlin && BuildSystem.isWindows()) {
                var windowsBuild = new ArrayList<>(List.of("cmd", "/c")); windowsBuild.addAll(build); build = windowsBuild;
            }
            run(project, build);
            initialize(project);
        }
        String unchangedConfiguration = Files.readString(main.resolve(".mcp.json"));
        ProjectIdentity a = ProjectIdentity.resolve(main), b = ProjectIdentity.resolve(linked);
        assertEquals(a.repositoryId(), b.repositoryId());
        assertNotEquals(a.worktreeId(), b.worktreeId());
        ProjectRegistry registry = new ProjectRegistry(); registry.register(main);
        QuillTools tools = new QuillTools(registry);
        try (var callers = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                boolean original = i % 2 == 0;
                futures.add(callers.submit(() -> {
                    try {
                        var identity = original ? a : b;
                        String method = original ? "first" : "second";
                        var answer = JSON.readTree(tools.search_symbols(method, Optional.of("method"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(identity.projectRoot().toString())));
                        assertTrue(answer.path("total").asInt() > 0, answer.toString());
                        assertEquals(identity.projectId(), answer.path("_meta").path("project_id").asText());
                        assertEquals(identity.worktreeId(), answer.path("_meta").path("worktree_id").asText());
                        var absent = JSON.readTree(tools.search_symbols(original ? "second" : "first", Optional.of("method"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(identity.projectRoot().toString())));
                        assertEquals(0, absent.path("total").asInt(), absent.toString());
                    } catch (Exception failure) { throw new RuntimeException(failure); }
                }));
            }
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
        }
        var legacy = JSON.readTree(tools.search_symbols("first", Optional.of("method"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        assertTrue(legacy.path("total").asInt() > 0, legacy.toString());
        assertEquals(a.projectId(), legacy.path("_meta").path("project_id").asText());
        var legacyAbsent = JSON.readTree(tools.search_symbols("second", Optional.of("method"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        assertEquals(0, legacyAbsent.path("total").asInt(), legacyAbsent.toString());
        assertEquals(unchangedConfiguration, Files.readString(main.resolve(".mcp.json")));
        assertEquals(unchangedConfiguration, Files.readString(linked.resolve(".mcp.json")));
        // A copied generation cannot become queryable even at the same Git commit.
        org.treblereel.mcp.workspace.WorkspaceManifestStore.initialize(temp, 1);
        ProjectRegistry workspace = new ProjectRegistry(new org.treblereel.mcp.mcp.WorkspaceProjectScope(temp));
        var ambiguous = JSON.readTree(new QuillTools(workspace).resolve_workspace_entity("fixture.Api"));
        assertEquals("ambiguous", ambiguous.path("resolution").asText(), ambiguous.toString());
        workspace.resolve().projects().forEach(entry -> org.treblereel.mcp.db.QuillDatabase.retire(entry.jdbi()));
        Path foreign = ProjectIndexStore.findBestAvailableDb(main);
        registry.resolve(main.toString()).projects().forEach(entry -> org.treblereel.mcp.db.QuillDatabase.retire(entry.jdbi()));
        registry.resolve(linked.toString()).projects().forEach(entry -> org.treblereel.mcp.db.QuillDatabase.retire(entry.jdbi()));
        try (var entries = Files.list(linked.resolve(".quill"))) {
            for (Path file : entries.filter(path -> path.toString().endsWith(".db")).toList()) Files.delete(file);
        }
        Files.copy(foreign, linked.resolve(".quill").resolve(foreign.getFileName()));
        Files.copy(main.resolve(".quill/refs.json"), linked.resolve(".quill/refs.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        var rejected = JSON.readTree(tools.search_symbols("first", Optional.of("method"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(linked.toString())));
        assertEquals("INDEX_OWNERSHIP_MISMATCH", rejected.path("error_code").asText(), rejected.toString());
        assertNull(ProjectIndexStore.findBestAvailableDb(linked));
        assertNotNull(ProjectIndexStore.findBestAvailableDb(main));
    }

    private void writeSource(Path project, boolean kotlin, String method) throws Exception {
        Path file = project.resolve(kotlin ? "src/main/kotlin/fixture/Api.kt" : "src/main/java/fixture/Api.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, kotlin ? "package fixture\nclass Api { fun " + method + "(value: Int) = value }\n"
                : "package fixture; public class Api { public int " + method + "(int value) { return value; } }\n");
    }

    private void initialize(Path project) throws Exception {
        if (!Boolean.getBoolean("native.tests.required")) { assertTrue(ProjectInitializer.initialize(project, true)); return; }
        Path binary = REPOSITORY.resolve(BuildSystem.isWindows() ? "quill-app/target/quill.exe" : "quill-app/target/quill");
        run(project, List.of(binary.toString(), "init", "--index-only", "--project", project.toString()));
    }
    private static void git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString())); command.addAll(List.of(args)); run(root, command);
    }
    private static void run(Path root, List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).inheritIO();
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        Process process = builder.start();
        try { assertTrue(process.waitFor(3, TimeUnit.MINUTES), "Timed out: " + command); assertEquals(0, process.exitValue(), command.toString()); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
