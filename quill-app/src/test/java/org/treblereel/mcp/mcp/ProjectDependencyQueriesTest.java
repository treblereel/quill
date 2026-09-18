package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        Path second = temp.resolve("worker/build/quill-classpath.txt");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.writeString(first, mavenJar.toString());
        Files.writeString(second, gradleJar.toString());

        var result = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(temp, null, null, 100, 0));

        assertEquals(2, result.path("total").asInt());
        assertFalse(result.path("discovery").path("build_invoked").asBoolean());
        assertEquals("com.acme:widget:1.2:tests",
                result.path("dependencies").get(0).path("id").asText());
        assertEquals("api",
                result.path("dependencies").get(0).path("used_by_modules").get(0).asText());
        assertEquals("org.demo:lib:3.0",
                result.path("dependencies").get(1).path("id").asText());
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

        var filtered = JSON.readTree(new ProjectDependencyQueries()
                .getProjectDependencies(temp, ".", "beta", 1, 0));

        assertEquals(1, filtered.path("total").asInt());
        assertEquals("b:beta:2", filtered.path("dependencies").get(0).path("id").asText());
    }
}
