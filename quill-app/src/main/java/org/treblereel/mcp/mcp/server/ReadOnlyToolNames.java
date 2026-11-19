package org.treblereel.mcp.mcp.server;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.treblereel.mcp.mcp.tools.QuillTools;
import org.treblereel.mcp.mcp.tools.Tool;

/** Concrete permission names, derived from the same read-only contracts as MCP annotations. */
public final class ReadOnlyToolNames {
    private ReadOnlyToolNames() {}

    /** Claude's rule for every tool of the quill server; every Quill tool only reads, so init grants this one. */
    public static final String SERVER_RULE = "mcp__quill";

    public static List<String> all() {
        return Stream.of(QuillTools.class, RouterTools.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .filter(method -> method.getAnnotation(Tool.class).readOnly())
                .map(method -> "mcp__quill__" + method.getName())
                .distinct().sorted().toList();
    }
}
