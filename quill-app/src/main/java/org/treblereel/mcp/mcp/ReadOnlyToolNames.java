package org.treblereel.mcp.mcp;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Concrete permission names, derived from the same read-only contracts as MCP annotations. */
public final class ReadOnlyToolNames {
    private ReadOnlyToolNames() {}

    public static List<String> all() {
        return Stream.of(QuillTools.class, RouterTools.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .filter(method -> method.getAnnotation(Tool.class).readOnly())
                .map(method -> "mcp__quill__" + method.getName())
                .distinct().sorted().toList();
    }
}
