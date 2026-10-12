package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.McpStdioServer;
import org.treblereel.mcp.mcp.McpToolProfile;
import org.treblereel.mcp.mcp.ProjectRegistry;
import org.treblereel.mcp.mcp.WorkspaceProjectScope;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;

class MavenExtensionUpdaterTest {
    @TempDir Path root;

    private static String extension(String group, String version) {
        return "<extension><groupId>" + group + "</groupId><artifactId>quill-maven-extension</artifactId>"
                + "<version>" + version + "</version></extension>";
    }

    private Path configuration(Path project, String content) throws Exception {
        Files.createDirectories(project.resolve(".mvn"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        return Files.writeString(project.resolve(".mvn/extensions.xml"), content);
    }

    @Test void upgradesManagedIntegrationAndSecondStartupDoesNotWrite() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        BuildIntegrationInstaller.install(root);
        Path file = root.resolve(".mvn/extensions.xml");
        String current = Files.readString(file);
        Files.writeString(file, current.replace("<version>0.1.1</version>", "<version>0.1.0</version>"));
        MavenExtensionUpdater.upgrade(root);
        assertEquals(current, Files.readString(file));
        FileTime old = FileTime.fromMillis(1000);
        Files.setLastModifiedTime(file, old);
        MavenExtensionUpdater.upgrade(root);
        assertEquals(old, Files.getLastModifiedTime(file));
    }

    @Test void upgradesManualEntryPreservingCommentsWhitespaceAndOtherExtensions() throws Exception {
        String content = "<?xml version=\"1.0\"?>\r\n<extensions>\r\n<!-- "
                + extension("org.treblereel.mcp", "0.1.0") + " -->\r\n"
                + extension("other", "0.1.1") + "\r\n"
                + extension("org.treblereel.mcp", " \n 0.1.0 \t") + "\r\n</extensions>";
        Path file = configuration(root, content);
        assertEquals(BuildIntegrationInstaller.State.OUTDATED, BuildIntegrationInstaller.inspect(root).state());
        MavenExtensionUpdater.upgrade(root);
        assertEquals(content.replace(" \n 0.1.0 \t", " \n 0.1.1 \t"), Files.readString(file));
        assertEquals(BuildIntegrationInstaller.State.INSTALLED, BuildIntegrationInstaller.inspect(root).state());
    }

    @Test void handlesNamespacedDescriptor() throws Exception {
        String content = "<m:extensions xmlns:m=\"urn:maven\">" + extension("org.treblereel.mcp", "0.1.0")
                .replace("<", "<m:").replace("<m:/", "</m:") + "</m:extensions>";
        Path file = configuration(root, content);
        MavenExtensionUpdater.upgrade(root);
        assertEquals(content.replace("0.1.0", "0.1.1"), Files.readString(file));
    }

    @TestFactory Stream<DynamicTest> upgradesOlderMavenVersions() {
        return Stream.of("0.0.9", "0.1.0", "0.1.1-SNAPSHOT", "0.1.1-rc1").map(version ->
                DynamicTest.dynamicTest(version, () -> {
                    Path project = Files.createDirectories(root.resolve(version));
                    Path file = configuration(project, "<extensions>"
                            + extension("org.treblereel.mcp", version) + "</extensions>");
                    MavenExtensionUpdater.upgrade(project);
                    assertTrue(Files.readString(file).contains("<version>0.1.1</version>"));
                }));
    }

    @Test void explicitInitUpdatesManualEntryWithoutCreatingDuplicate() throws Exception {
        Path file = configuration(root, "<extensions>" + extension("org.treblereel.mcp", "0.1.0") + "</extensions>");
        assertEquals(BuildIntegrationInstaller.Result.UPDATED, BuildIntegrationInstaller.install(root));
        assertEquals(1, Files.readString(file).split("<artifactId>quill-maven-extension</artifactId>", -1).length - 1);
    }

    @Test void findsReactorConfigurationFromModule() throws Exception {
        Path file = configuration(root, "<extensions>" + extension("org.treblereel.mcp", "0.1.0") + "</extensions>");
        Path module = Files.createDirectories(root.resolve("module"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        MavenExtensionUpdater.upgrade(module);
        assertTrue(Files.readString(file).contains("<version>0.1.1</version>"));
    }

    @Test void preservesSymbolicLinkToExtensionConfiguration() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        Path file = configuration(root, "<extensions>" + extension("org.treblereel.mcp", "0.1.0") + "</extensions>");
        Path shared = root.resolve("shared-extensions.xml");
        Files.move(file, shared);
        Files.createSymbolicLink(file, Path.of("../shared-extensions.xml"));
        MavenExtensionUpdater.upgrade(root);
        assertTrue(Files.isSymbolicLink(file));
        assertTrue(Files.readString(shared).contains("<version>0.1.1</version>"));
    }

    @Test void nearerMavenDirectoryStopsAncestorLookup() throws Exception {
        Path file = configuration(root, "<extensions>" + extension("org.treblereel.mcp", "0.1.0") + "</extensions>");
        String before = Files.readString(file);
        Path module = Files.createDirectories(root.resolve("module/.mvn")).getParent();
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        MavenExtensionUpdater.upgrade(module);
        assertEquals(before, Files.readString(file));
    }

    @Test void missingIntegrationIsNotInstalledAtStartup() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        MavenExtensionUpdater.upgrade(root);
        assertFalse(Files.exists(root.resolve(".mvn")));
    }

    @Test void gradleAndUnsupportedProjectsAreUntouched() throws Exception {
        Path file = configuration(root, "<extensions>" + extension("org.treblereel.mcp", "0.1.0") + "</extensions>");
        Files.delete(root.resolve("pom.xml"));
        String before = Files.readString(file);
        MavenExtensionUpdater.upgrade(root);
        Files.writeString(root.resolve("build.gradle"), "");
        MavenExtensionUpdater.upgrade(root);
        assertEquals(before, Files.readString(file));
    }

    @TestFactory Stream<DynamicTest> invalidOrUnneededUpdatesPreserveFile() {
        String old = extension("org.treblereel.mcp", "0.1.0");
        List<String> contents = List.of(
                "<extensions>" + extension("org.treblereel.mcp", "0.1.10") + "</extensions>",
                "<extensions>" + extension("org.treblereel.mcp", "0.1.1") + "</extensions>",
                "<extensions>" + extension("org.treblereel.mcp", "${extension.version}") + "</extensions>",
                "<extensions>" + extension("org.treblereel.mcp", "LATEST") + "</extensions>",
                "<extensions>" + extension("org.treblereel.mcp", "RELEASE") + "</extensions>",
                "<extensions>" + extension("org.treblereel.mcp", "[0.1,1)") + "</extensions>",
                "<extensions>" + extension("org.treblereel.mcp", "") + "</extensions>",
                "<extensions>" + extension("other", "0.1.0") + "</extensions>",
                "<extensions>" + old + old + "</extensions>",
                "<extensions>" + old.replace("<version>0.1.0</version>", "") + "</extensions>",
                "<extensions>" + old.replace("<version>0.1.0</version>", "<version>0.1.0</version><version>0.1.0</version>") + "</extensions>",
                "<extensions>" + old,
                "<extensions><!-- quill:build-integration:start -->" + old + "</extensions>",
                "<!DOCTYPE extensions [<!ENTITY version SYSTEM 'file:///etc/passwd'>]><extensions>" + old + "</extensions>",
                "<extensions>" + old.replace("0.1.0", "0.1<!--comment-->.0") + "</extensions>",
                "<extensions><!-- " + old + " --></extensions>");
        return java.util.stream.IntStream.range(0, contents.size()).mapToObj(index -> DynamicTest.dynamicTest(
                "preserve case " + index, () -> {
                    Path project = Files.createDirectories(root.resolve("case-" + index));
                    String content = contents.get(index);
                    Path file = configuration(project, content);
                    MavenExtensionUpdater.upgrade(project);
                    assertEquals(content, Files.readString(file));
                }));
    }

    @Test void actualMcpStartupUpgradesBeforePrewarmEvenWithoutIndex() throws Exception {
        Path file = configuration(root, "<extensions>" + extension("org.treblereel.mcp", "0.1.0") + "</extensions>");
        ProjectRegistry registry = new ProjectRegistry() {
            @Override public void prewarm() {
                try { assertTrue(Files.readString(file).contains("<version>0.1.1</version>")); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
        };
        registry.register(root);
        McpStdioServer.start(registry, new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(), McpToolProfile.parse("router"));
        assertTrue(Files.readString(file).contains("<version>0.1.1</version>"));
    }

    @Test void workspaceUpgradesEachRepository() throws Exception {
        WorkspaceManifestStore.initialize(root, 1);
        Path first = Files.createDirectories(root.resolve("first/.git")).getParent();
        Path second = Files.createDirectories(root.resolve("second/.git")).getParent();
        Path file1 = configuration(first, "<extensions>" + extension("org.treblereel.mcp", "0.1.0") + "</extensions>");
        Path file2 = configuration(second, "<extensions>" + extension("org.treblereel.mcp", "0.1.0") + "</extensions>");
        new ProjectRegistry(new WorkspaceProjectScope(root)).upgradeBuildIntegrations();
        assertTrue(Files.readString(file1).contains("<version>0.1.1</version>"));
        assertTrue(Files.readString(file2).contains("<version>0.1.1</version>"));
    }
}
