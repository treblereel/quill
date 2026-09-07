package org.treblereel.mcp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.treblereel.mcp.core.GitHookInstaller;

public final class QuillLauncher {

    private QuillLauncher() {}

    public static GitHookInstaller.Launcher detect() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            return ProcessHandle.current().info().command()
                    .map(Path::of)
                    .map(path -> new GitHookInstaller.Launcher(
                            List.of(path.toString()), path))
                    .orElse(null);
        }

        try {
            URI location = QuillTopCommand.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path artifact = Path.of(location).toAbsolutePath().normalize();
            if (Files.isRegularFile(artifact) && artifact.toString().endsWith(".jar")) {
                Path java = Path.of(System.getProperty("java.home"), "bin", "java")
                        .toAbsolutePath().normalize();
                return new GitHookInstaller.Launcher(
                        List.of(java.toString(), "-jar", artifact.toString()), artifact);
            }
        } catch (Exception ignored) {
            // Development classpath or unavailable code source: fall back to PATH lookup.
        }
        return null;
    }
}
