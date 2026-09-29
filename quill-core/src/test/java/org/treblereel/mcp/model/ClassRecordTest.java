package org.treblereel.mcp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClassRecordTest {

    @Test
    void changingBeanFlagPreservesIndexMetadata() {
        ClassRecord original = new ClassRecord(7, "example.ConfigBean", "CLASS",
                "java.lang.Object", List.of("java.io.Serializable"),
                "module-a/src/main/java/example/ConfigBean.java", 12, false, 48,
                31, "generated", "current", "module-a", "main");

        ClassRecord bean = original.withBean(true);

        assertFalse(original.isBean());
        assertTrue(bean.isBean());
        assertEquals(original.id(), bean.id());
        assertEquals(original.className(), bean.className());
        assertEquals(original.kind(), bean.kind());
        assertEquals(original.superclass(), bean.superclass());
        assertEquals(original.interfaces(), bean.interfaces());
        assertEquals(original.sourceFile(), bean.sourceFile());
        assertEquals(original.sourceLine(), bean.sourceLine());
        assertEquals(original.sourceTokens(), bean.sourceTokens());
        assertEquals(original.fileId(), bean.fileId());
        assertEquals(original.origin(), bean.origin());
        assertEquals(original.lifecycle(), bean.lifecycle());
        assertEquals(original.module(), bean.module());
        assertEquals(original.sourceSet(), bean.sourceSet());
    }
}
