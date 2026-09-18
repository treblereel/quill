package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class ProjectDependencyQueriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temp;

    @Test
    void reportsMavenAndGradleArtifactsByConsumingModuleWithoutBuilding() throws Exception {
        Path mavenJar = temp.resolve("home/.m2/repository/com/acme/widget/1.2/widget-1.2-tests.jar");
        Path gradleJar = temp.resolve(
                "home/.gradle/caches/modules-2/files-2.1/org.demo/lib/3.0/hash/lib-3.0.jar");
        Files.createDirectories(mavenJar.getParent());
        Files.createDirectories(gradleJar.getParent());
        Files.write(mavenJar, new byte[] {1});
        Files.write(gradleJar, new byte[] {1});
        Path first = temp.resolve("api/target/quill-classpath.txt");
        Path second = temp.resolve("worker/target/quill-classpath.txt");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, mavenJar.toString());
        Files.writeString(second, gradleJar.toString());
        Files.writeString(temp.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>root</artifactId><version>1</version>
                  <modules><module>api</module><module>worker</module></modules>
                </project>
                """);
        Files.writeString(temp.resolve("api/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>api</artifactId><version>1</version>
                  <dependencies><dependency><groupId>com.acme</groupId>
                    <artifactId>widget</artifactId><version>1.2</version>
                  </dependency></dependencies>
                </project>
                """);
        Files.writeString(temp.resolve("worker/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>worker</artifactId><version>1</version>
                </project>
                """);
        Jdbi jdbi = database();
        module(jdbi, "api");
        module(jdbi, "worker");

        var result = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(jdbi, temp, null, null, 100, 0));

        assertEquals(2, result.path("total").asInt());
        assertFalse(result.path("discovery").path("build_invoked").asBoolean());
        assertEquals("com.acme:widget:1.2:tests",
                result.path("dependencies").get(0).path("id").asText());
        assertEquals("api",
                result.path("dependencies").get(0).path("used_by_modules").get(0).asText());
        assertEquals(true, result.path("dependencies").get(0).path("direct").asBoolean());
        assertEquals("api",
                result.path("dependencies").get(0).path("direct_in_modules").get(0).asText());
        assertEquals("org.demo:lib:3.0",
                result.path("dependencies").get(1).path("id").asText());
        assertFalse(result.path("dependencies").get(1).path("direct").asBoolean());
        assertEquals("worker",
                result.path("dependencies").get(1).path("transitive_in_modules").get(0).asText());
    }

    @Test
    void filtersAndPaginatesArtifacts() throws Exception {
        Path repository = temp.resolve(".m2/repository");
        Path alpha = repository.resolve("a/alpha/1/alpha-1.jar");
        Path beta = repository.resolve("b/beta/2/beta-2.jar");
        Files.createDirectories(alpha.getParent());
        Files.createDirectories(beta.getParent());
        Files.write(alpha, new byte[] {1});
        Files.write(beta, new byte[] {1});
        Path classpath = temp.resolve("target/quill-classpath.txt");
        Files.createDirectories(classpath.getParent());
        Files.writeString(classpath, alpha + java.io.File.pathSeparator + beta);
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Jdbi jdbi = database();
        module(jdbi, ".");

        var filtered = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(jdbi, temp, ".", "beta", 1, 0));

        assertEquals(1, filtered.path("total").asInt());
        assertEquals("b:beta:2", filtered.path("dependencies").get(0).path("id").asText());
    }

    @Test
    void prewarmsAndInvalidatesMetadataWhenClasspathChanges() throws Exception {
        Path repository = temp.resolve(".m2/repository");
        Path alpha = repository.resolve("a/alpha/1/alpha-1.jar");
        Path beta = repository.resolve("b/beta/2/beta-2.jar");
        Files.createDirectories(alpha.getParent());
        Files.createDirectories(beta.getParent());
        Files.write(alpha, new byte[] {1});
        Files.write(beta, new byte[] {1});
        Path classpath = temp.resolve("target/quill-classpath.txt");
        Files.createDirectories(classpath.getParent());
        Files.writeString(classpath, alpha.toString());
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Jdbi jdbi = database();
        module(jdbi, ".");

        ProjectDependencyQueries.prewarm(jdbi, temp);
        var warm = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(jdbi, temp, null, null, 100, 0));
        assertEquals("hit", warm.path("discovery").path("metadata_cache").asText());

        Files.writeString(classpath, alpha + java.io.File.pathSeparator + beta);
        var refreshed = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(jdbi, temp, null, null, 100, 0));
        assertEquals("miss", refreshed.path("discovery").path("metadata_cache").asText());
        assertEquals(2, refreshed.path("total").asInt());
    }

    private Jdbi database() {
        return QuillDatabase.create(temp.resolve("index-" + System.nanoTime() + ".db"));
    }

    private static void module(Jdbi jdbi, String module) {
        String path = module.equals(".") ? "src/main/java/App.java"
                : module + "/src/main/java/App.java";
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO files(project_path, repository_path, kind, origin, lifecycle,
                                          module, source_set)
                        VALUES (:path, :path, 'java', 'source', 'current', :module, 'main')
                        """).bind("path", path).bind("module", module).execute());
    }
}
