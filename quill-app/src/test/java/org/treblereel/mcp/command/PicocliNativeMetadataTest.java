package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PicocliNativeMetadataTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GENERATED_CONFIG =
            "META-INF/native-image/picocli-generated/org.treblereel.mcp/"
                    + "quill-app/reflect-config.json";

    @Test
    void annotationProcessorGeneratesMetadataForEveryCommand() throws Exception {
        JsonNode commands;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(GENERATED_CONFIG)) {
            assertNotNull(input, "Picocli native metadata should be generated during compilation");
            commands = JSON.readTree(input);
        }

        Set<String> names = new HashSet<>();
        commands.forEach(command -> names.add(command.path("name").asText()));
        assertTrue(names.containsAll(Set.of(
                "org.treblereel.mcp.QuillTopCommand",
                "org.treblereel.mcp.command.InitCommand",
                "org.treblereel.mcp.command.UpdateCommand",
                "org.treblereel.mcp.command.StatusCommand",
                "org.treblereel.mcp.command.DoctorCommand",
                "org.treblereel.mcp.command.CleanCommand")));

        JsonNode init = findCommand(commands, InitCommand.class.getName());
        Set<String> fields = new HashSet<>();
        init.path("fields").forEach(field -> fields.add(field.path("name").asText()));
        assertEquals(Set.of("projectPath", "indexOnly", "timings"), fields);
    }

    @Test
    void manualMetadataDoesNotDuplicateGeneratedCommands() throws Exception {
        String manualConfig =
                "META-INF/native-image/org.treblereel.mcp/quill-app/reflect-config.json";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(manualConfig)) {
            assertNotNull(input);
            JsonNode entries = JSON.readTree(input);
            for (JsonNode entry : entries) {
                assertFalse(entry.path("name").asText().startsWith("org.treblereel.mcp.command."),
                        "Picocli command metadata belongs in generated configuration");
            }
        }
    }

    @Test
    void toolMetadataKeepsOnlyPublicMethods() throws Exception {
        String manualConfig =
                "META-INF/native-image/org.treblereel.mcp/quill-app/reflect-config.json";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(manualConfig)) {
            assertNotNull(input);
            JsonNode entries = JSON.readTree(input);
            for (String type : Set.of("org.treblereel.mcp.mcp.QuillTools",
                    "org.treblereel.mcp.mcp.RouterTools")) {
                JsonNode entry = findCommand(entries, type);
                assertTrue(entry.path("allPublicMethods").asBoolean());
                assertFalse(entry.has("allDeclaredMethods"));
            }
        }
    }

    @Test
    void workspaceStateRecordIsAvailableToJacksonInNativeImages() throws Exception {
        String manualConfig =
                "META-INF/native-image/org.treblereel.mcp/quill-app/reflect-config.json";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(manualConfig)) {
            assertNotNull(input);
            JsonNode entries = JSON.readTree(input);
            JsonNode state = findCommand(entries,
                    "org.treblereel.mcp.workspace.WorkspaceRepositoryStateStore$Repository");
            assertTrue(state.path("allDeclaredConstructors").asBoolean());
            assertTrue(state.path("allPublicMethods").asBoolean());
        }
    }

    @Test
    void externalBeanRecordsAreAvailableToJacksonInNativeImages() throws Exception {
        String manualConfig =
                "META-INF/native-image/org.treblereel.mcp/quill-app/reflect-config.json";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(manualConfig)) {
            JsonNode entries = JSON.readTree(input);
            for (String type : Set.of(
                    "org.treblereel.mcp.model.ExternalBeanRecord",
                    "org.treblereel.mcp.model.ExternalInjectionPointRecord")) {
                JsonNode record = findCommand(entries, type);
                assertTrue(record.path("allDeclaredConstructors").asBoolean());
                assertTrue(record.path("allPublicMethods").asBoolean());
            }
        }
    }

    private static JsonNode findCommand(JsonNode commands, String name) {
        for (JsonNode command : commands) {
            if (name.equals(command.path("name").asText())) return command;
        }
        throw new AssertionError("Missing generated metadata for " + name);
    }
}
