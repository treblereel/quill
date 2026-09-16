package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenProjectDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversNestedAndExistingProfileModules() throws Exception {
        Path root = module("root", """
                <properties><service.dir>service</service.dir></properties>
                <modules>
                  <module>common</module>
                  <module>${service.dir}</module>
                  <module>nested</module>
                </modules>
                <profiles>
                  <profile>
                    <id>optional</id>
                    <modules>
                      <module>profile-module</module>
                      <module>missing-profile-module</module>
                    </modules>
                  </profile>
                </profiles>
                """);
        Path common = childModule(root, "common", "");
        Path service = childModule(root, "service", "");
        Path nested = childModule(root, "nested", "<modules><module>child</module></modules>");
        Path child = childModule(nested, "child", "");
        Path profile = childModule(root, "profile-module", "");

        MavenProjectDiscovery.Discovery discovery = MavenProjectDiscovery.discover(root);

        assertTrue(discovery.complete());
        assertEquals(Set.of(root, common, service, nested, child, profile),
                Set.copyOf(discovery.moduleDirectories()));
    }

    @Test
    void reportsIncompleteReactorWhenDeclaredModuleIsMissing() throws Exception {
        Path root = module("root", "<modules><module>missing</module></modules>");

        MavenProjectDiscovery.Discovery discovery = MavenProjectDiscovery.discover(root);

        assertFalse(discovery.complete());
        assertEquals(Set.of(root), Set.copyOf(discovery.moduleDirectories()));
    }

    @Test
    void findsNearestContainingReactorFromChildModule() throws Exception {
        Path outer = module("outer", "<modules><module>nested</module></modules>");
        Path nested = childModule(outer, "nested", "<modules><module>service</module></modules>");
        Path service = childModule(nested, "service", "");

        assertEquals(nested, MavenProjectDiscovery.findReactorRoot(service));
        assertEquals(nested, ProjectRootFinder.findReactor(service.resolve("src/main/java")));
        assertEquals(service, ProjectRootFinder.find(service));
    }

    @Test
    void doesNotPromoteProjectUnderUnrelatedAncestorPom() throws Exception {
        Path outer = module("outer", "<modules><module>other</module></modules>");
        childModule(outer, "other", "");
        Path standalone = childModule(outer, "standalone", "");

        assertEquals(standalone, MavenProjectDiscovery.findReactorRoot(standalone));
    }

    @Test
    void breaksModuleCycles() throws Exception {
        Path root = module("root", "<modules><module>child</module></modules>");
        Path child = childModule(root, "child", "<modules><module>..</module></modules>");

        MavenProjectDiscovery.Discovery discovery = MavenProjectDiscovery.discover(root);

        assertTrue(discovery.complete());
        assertEquals(Set.of(root, child), Set.copyOf(discovery.moduleDirectories()));
    }

    private Path module(String name, String body) throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve(name));
        writePom(directory, name, body);
        return directory;
    }

    private static Path childModule(Path parent, String name, String body) throws Exception {
        Path directory = Files.createDirectories(parent.resolve(name));
        writePom(directory, name, body);
        return directory;
    }

    private static void writePom(Path directory, String artifactId, String body) throws Exception {
        Files.writeString(directory.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>%s</artifactId>
                  <version>1</version>
                  <packaging>pom</packaging>
                  %s
                </project>
                """.formatted(artifactId, body));
    }
}
