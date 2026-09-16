package org.treblereel.mcp;

import java.nio.file.Path;

public final class QuillLauncher {

    private QuillLauncher() {}

    public static String detect() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") == null) return null;
        return ProcessHandle.current().info().command()
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize().toString())
                .orElse(null);
    }
}
