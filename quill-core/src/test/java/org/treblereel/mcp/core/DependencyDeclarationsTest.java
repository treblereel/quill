package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * V48 CQ-004: Codex grepped pom.xml three times to cite where quarkus-vertx is declared and where
 * its version comes from.
 */
class DependencyDeclarationsTest {
    @TempDir Path temp;

    @Test
    void aDirectDependencyStatesItsDeclarationLineAndWhereItsVersionComesFrom() throws Exception {
        Path repository = temp.resolve("m2");
        Path bom = repository.resolve("org/acme/acme-bom/7/acme-bom-7.pom");
        Files.createDirectories(bom.getParent());
        Files.writeString(
                bom,
                """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId><artifactId>acme-bom</artifactId><version>7</version>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.acme</groupId><artifactId>widget</artifactId><version>7</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        Files.writeString(
                project.resolve("pom.xml"),
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>app</artifactId><version>1</version>
                  <properties>
                    <acme.version>7</acme.version>
                    <lib.version>2.1</lib.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.acme</groupId>
                        <artifactId>acme-bom</artifactId>
                        <version>${acme.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.acme</groupId>
                      <artifactId>widget</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.other</groupId>
                      <artifactId>lib</artifactId>
                      <version>${lib.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        var declarations = DependencyDeclarations.locate(project, ".", repository);

        assertEquals(List.of("pom.xml:22"), declarations.get("org.acme:widget").declaredAt());
        assertEquals(
                "managed by imported BOM org.acme:acme-bom:7 at pom.xml:12 (version property acme.version at pom.xml:5)",
                declarations.get("org.acme:widget").versionSource());
        assertEquals(List.of("pom.xml:26"), declarations.get("org.other:lib").declaredAt());
        assertEquals(
                "version property lib.version=2.1 at pom.xml:6",
                declarations.get("org.other:lib").versionSource());
    }

    /** Apollo AQ-001: Claude read pom.xml twice for the Spring Boot and Spring Cloud BOMs. */
    @Test
    void importedBomsStateTheirCoordinatesLineAndVersionProperty() throws Exception {
        Path project = temp.resolve("boms");
        Files.createDirectories(project.resolve("web"));
        Files.writeString(
                project.resolve("pom.xml"),
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>app</artifactId><version>1</version>
                  <properties>
                    <cloud.version>2025.1</cloud.version>
                  </properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.cloud</groupId>
                        <artifactId>spring-cloud-dependencies</artifactId>
                        <version>${cloud.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                      <dependency>
                        <groupId>org.other</groupId>
                        <artifactId>managed</artifactId>
                        <version>3</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        Files.writeString(
                project.resolve("web/pom.xml"),
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>test</groupId><artifactId>app</artifactId><version>1</version></parent>
                  <artifactId>web</artifactId>
                </project>
                """);

        assertEquals(
                List.of(
                        "org.springframework.cloud:spring-cloud-dependencies:2025.1 at pom.xml:11 (version property cloud.version at pom.xml:5)"),
                DependencyDeclarations.importedBoms(project, "."));
        // A module inherits its parent's imports.
        assertEquals(
                DependencyDeclarations.importedBoms(project, "."),
                DependencyDeclarations.importedBoms(project, "web"));
    }
}
