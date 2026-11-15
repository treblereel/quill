package org.treblereel.mcp.mcp.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Codex defers MCP tool schemas (tool_search_always_defer_mcp_tools) and reads them by filtering
 * ALL_TOOLS: in V41 206 of its 547 script cells did that. AGENTS.md is the one Quill text it reads.
 */
class CodexToolGuideTest {

    @Test void listsEveryCodexToolWithItsArgumentsAndNamesTheRoutedOnes() {
        String guide = CodexToolGuide.text();
        assertTrue(guide.contains("`list_beans({class_name?"), guide);
        assertTrue(guide.contains("`get_symbol_details({target,"), guide);
        assertTrue(guide.contains("`get_type_hierarchy({target"), guide);
        assertTrue(guide.contains("execute_tool({name, arguments})"), guide);
        assertTrue(guide.contains("find_git_hotspots"), "a tool outside the codex profile is named for execute_tool");
        assertFalse(guide.contains("project?"), "single-project servers take no project argument");
        assertFalse(guide.contains("`find_git_hotspots({"), "routed tools are named, not spelled out");
    }
}
