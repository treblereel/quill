package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Apollo AQ-001 (Claude): asked which dependencies bring Spring Boot and Spring Cloud, the project
 * fact listed only test starters; Claude read pom.xml twice and queried dependencies five times.
 */
class ProjectBuildDeclarationsTest {
    @TempDir Path root;

    @Test
    void theProjectFactNamesImportedBomsAndEachModulesStarters() throws Exception {
        Files.createDirectories(root.resolve("portal"));
        Files.createDirectories(root.resolve("common"));
        Files.writeString(
                root.resolve("pom.xml"),
                """
                <project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>app</artifactId><version>1</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-dependencies</artifactId>
                      <version>4.1.1</version><type>pom</type><scope>import</scope></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        Files.writeString(
                root.resolve("portal/pom.xml"),
                """
                <project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId><artifactId>app</artifactId><version>1</version></parent>
                  <artifactId>portal</artifactId>
                  <dependencies>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
                    <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-config</artifactId></dependency>
                    <dependency><groupId>t</groupId><artifactId>common</artifactId><version>1</version></dependency>
                  </dependencies>
                </project>
                """);
        Files.writeString(
                root.resolve("common/pom.xml"),
                """
                <project><modelVersion>4.0.0</modelVersion><parent><groupId>t</groupId><artifactId>app</artifactId><version>1</version></parent>
                  <artifactId>common</artifactId>
                </project>
                """);
        ObjectNode fact = new ObjectMapper().createObjectNode();
        NameTargetPrefetch.appendBuildDeclarations(
                fact, root, List.of("common/pom.xml", "mvnw", "pom.xml", "portal/pom.xml"));
        assertTrue(
                fact.path("imported_boms")
                        .toString()
                        .contains(
                                "org.springframework.boot:spring-boot-dependencies:4.1.1 at pom.xml:3"),
                fact.toString());
        assertEquals(
                "[\"portal: org.springframework.boot:spring-boot-starter-web, org.springframework.cloud:spring-cloud-starter-config\"]",
                fact.path("module_starters").toString());
    }
}
