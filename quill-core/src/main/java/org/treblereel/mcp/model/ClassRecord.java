package org.treblereel.mcp.model;

import java.util.List;

public record ClassRecord(
        int id,
        String className,
        String kind,
        String superclass,
        List<String> interfaces,
        String sourceFile,
        int sourceLine,
        boolean isBean,
        int sourceTokens,
        Integer fileId,
        String origin,
        String lifecycle
) {
    public ClassRecord(int id, String className, String kind, String superclass,
            List<String> interfaces, String sourceFile, int sourceLine, boolean isBean,
            int sourceTokens) {
        this(id, className, kind, superclass, interfaces, sourceFile, sourceLine, isBean,
                sourceTokens, null, "source", "current");
    }

    public ClassRecord withId(int newId) {
        return new ClassRecord(newId, className, kind, superclass, interfaces, sourceFile,
                sourceLine, isBean, sourceTokens, fileId, origin, lifecycle);
    }
}
