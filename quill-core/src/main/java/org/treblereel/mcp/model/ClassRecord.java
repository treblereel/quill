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
        String lifecycle,
        String module,
        String sourceSet
) {
    public ClassRecord {
        interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
    }

    public ClassRecord(int id, String className, String kind, String superclass,
            List<String> interfaces, String sourceFile, int sourceLine, boolean isBean,
            int sourceTokens) {
        this(id, className, kind, superclass, interfaces, sourceFile, sourceLine, isBean,
                sourceTokens, null, "source", "current", null, null);
    }

    public ClassRecord(int id, String className, String kind, String superclass,
            List<String> interfaces, String sourceFile, int sourceLine, boolean isBean,
            int sourceTokens, Integer fileId, String origin, String lifecycle) {
        this(id, className, kind, superclass, interfaces, sourceFile, sourceLine, isBean,
                sourceTokens, fileId, origin, lifecycle, null, null);
    }

    public static final java.util.Set<String> TEST_SOURCE_SETS =
            java.util.Set.of("test", "integration-test", "integrationTest");

    public boolean inTestSourceSet() {
        return sourceSet != null && TEST_SOURCE_SETS.contains(sourceSet);
    }

    public ClassRecord withId(int newId) {
        return new ClassRecord(newId, className, kind, superclass, interfaces, sourceFile,
                sourceLine, isBean, sourceTokens, fileId, origin, lifecycle, module, sourceSet);
    }

    public ClassRecord withBean(boolean bean) {
        return new ClassRecord(id, className, kind, superclass, interfaces, sourceFile,
                sourceLine, bean, sourceTokens, fileId, origin, lifecycle, module, sourceSet);
    }
}
