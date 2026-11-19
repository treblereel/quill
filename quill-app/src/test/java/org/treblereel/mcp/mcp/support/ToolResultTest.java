package org.treblereel.mcp.mcp.support;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ToolResultTest {

    @Test
    void objectAnswersStayParsedAndOtherTextStaysText() {
        assertInstanceOf(ToolResult.JsonAnswer.class, ToolResult.of("{\"classes\":[]}"));
        assertInstanceOf(ToolResult.Text.class, ToolResult.of("[1,2]"));
        assertInstanceOf(ToolResult.Text.class, ToolResult.of("{not json"));
        assertInstanceOf(ToolResult.Text.class, ToolResult.of(null));
        assertFalse(ToolResult.of("plain answer").error());
        assertEquals("plain answer", ToolResult.of("plain answer").text());
    }

    @Test
    void errorFollowsTheCurrentAndLegacyErrorFieldsOfTheFinishedTree() {
        assertTrue(ToolResult.of("{\"error_code\":\"X\",\"message\":\"m\"}").error());
        assertTrue(ToolResult.of("{\"error\":\"workspace_mode_required\"}").error());
        var answer = (ToolResult.JsonAnswer) ToolResult.of("{\"classes\":[]}");
        assertFalse(answer.error());
        answer.body().put("error_code", "LATE");
        assertTrue(answer.error(), "error is read from the tree as post-processing left it");
        assertTrue(ToolResult.failure("Missing required argument: target").error());
    }

    @Test
    void notFoundIsReadFromFieldsWhereverTheErrorKeySits() {
        assertTrue(notFound("{\"error_code\":\"CLASS_NOT_FOUND\",\"message\":\"Class not found: a.B\"}"));
        assertTrue(notFound("{\"annotation_indexed\":false}"));
        assertTrue(notFound("{\"message\":\"Not a bean: a.B\",\"error_code\":\"NOT_A_BEAN\"}"));
        assertTrue(notFound("{\"error\":\"Class not found\"}"));
        assertTrue(notFound("{\"target\":\"a.B\",\"error\":\"Class not found\"}"),
                "the legacy key need not come first, unlike the old string-prefix check");
        assertTrue(notFound("{\"error\":\"Not a bean: a.B\"}"));
        assertFalse(notFound("{\"message\":\"Class not found: a.B\"}"),
                "a message other than Not a bean decides on its own");
        assertFalse(notFound("{\"error\":\"workspace_mode_required\"}"));
        assertFalse(notFound("{\"classes\":[]}"));
    }

    private static boolean notFound(String json) {
        return ToolResult.isNotFound(((ToolResult.JsonAnswer) ToolResult.of(json)).body());
    }
}
