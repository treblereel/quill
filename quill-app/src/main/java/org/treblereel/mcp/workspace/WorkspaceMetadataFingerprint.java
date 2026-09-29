package org.treblereel.mcp.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.treblereel.mcp.core.BuildSystem;

/** Fingerprints the build metadata consumed by workspace-wide analysis. */
final class WorkspaceMetadataFingerprint {

    private WorkspaceMetadataFingerprint() {}

    static String compute(WorkspaceDiscovery.Result discovery,
            List<WorkspaceCoordinateCatalog.Module> modules, boolean includeClasspaths) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, discovery.fingerprint());
            Map<String, Path> roots = new TreeMap<>();
            discovery.repositories().forEach(repository ->
                    roots.put(repository.name(), repository.root()));
            modules.stream().sorted(Comparator.comparing(
                            WorkspaceCoordinateCatalog.Module::repository)
                    .thenComparing(WorkspaceCoordinateCatalog.Module::module))
                    .forEach(module -> {
                        Path repositoryRoot = roots.get(module.repository());
                        if (repositoryRoot == null) return;
                        Path moduleRoot = ".".equals(module.module())
                                ? repositoryRoot : repositoryRoot.resolve(module.module());
                        BuildSystem buildSystem = BuildSystem.valueOf(
                                module.buildSystem().toUpperCase(java.util.Locale.ROOT));
                        updateIdentity(digest, repositoryRoot,
                                moduleRoot.resolve(buildSystem == BuildSystem.MAVEN
                                        ? "pom.xml" : "build.gradle"));
                        if (buildSystem == BuildSystem.GRADLE) {
                            updateIdentity(digest, repositoryRoot,
                                    moduleRoot.resolve("build.gradle.kts"));
                            updateIdentity(digest, repositoryRoot,
                                    repositoryRoot.resolve("settings.gradle"));
                            updateIdentity(digest, repositoryRoot,
                                    repositoryRoot.resolve("settings.gradle.kts"));
                            updateIdentity(digest, repositoryRoot,
                                    repositoryRoot.resolve("gradle.properties"));
                        }
                        if (includeClasspaths) {
                            updateIdentity(digest, repositoryRoot,
                                    buildSystem.classpathFile(moduleRoot));
                            updateIdentity(digest, repositoryRoot,
                                    buildSystem.testClasspathFile(moduleRoot));
                            updateIdentity(digest, repositoryRoot,
                                    buildSystem.classpathFingerprintFile(moduleRoot));
                        }
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void updateIdentity(MessageDigest digest, Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        update(digest, normalized.startsWith(root)
                ? root.relativize(normalized).toString().replace('\\', '/')
                : normalized.toString());
        if (!Files.isRegularFile(normalized)) {
            update(digest, "missing");
            return;
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    normalized, BasicFileAttributes.class);
            update(digest, attributes.lastModifiedTime().toString());
            update(digest, Long.toString(attributes.size()));
        } catch (IOException failure) {
            update(digest, "unreadable");
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
