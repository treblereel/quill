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
        int sourceTokens
) {
    public ClassRecord withId(int newId) {
        return new ClassRecord(newId, className, kind, superclass, interfaces, sourceFile, sourceLine, isBean, sourceTokens);
    }
}
