package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class KotlinSourceTextTest {
    @Test
    void masksNestedCommentsEscapedAndRawStringsButPreservesPositions() {
        String text = "/* outer /* package fake */ still comment */\n"
                + "val x = \"package fake \\\" escaped\"\n"
                + "val raw = \"\"\"\npackage fake\n\"\"\"\n"
                + "package org.`when` // trailing\nval `quoted name` = 1";
        String code = KotlinSourceText.code(text);
        assertEquals(text.length(), code.length());
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') assertEquals('\n', code.charAt(i));
        assertFalse(code.contains("fake"));
        assertTrue(code.contains("`quoted name`"));
        assertEquals("org.when", KotlinSourceText.packageName(text));
    }

    @Test
    void supportsDefaultPackageWhitespaceSemicolonAndUnterminatedComment() {
        assertEquals("", KotlinSourceText.packageName("// package foo\nclass Invoice"));
        assertEquals("org.acme", KotlinSourceText.packageName("package org . acme;\nclass Invoice"));
        assertEquals("", KotlinSourceText.packageName("/* package fake"));
        assertEquals("", KotlinSourceText.packageName("\"package fake\""));
    }
}
