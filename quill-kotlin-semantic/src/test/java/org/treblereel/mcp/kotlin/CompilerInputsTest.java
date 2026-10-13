package org.treblereel.mcp.kotlin;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("K2_ANALYZER")
class CompilerInputsTest {
    @TempDir Path temp;
    private ObjectNode request() throws Exception {
        Path file = temp.resolve("Caller.kt"); Files.writeString(file, "fun run() = 1");
        return new ObjectMapper().createObjectNode().put("module", temp.toString())
                .put("file", file.toString()).set("sources", new ObjectMapper().createArrayNode().add(file.toString()));
    }
    @Test void configurationEditsBetweenCollectionAndCompilerCaptureForceRetry() throws Exception {
        var request = request(); Path settings = temp.resolve("options.properties"); Files.writeString(settings, "languageVersion=2.3");
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(settings)));
        request.putArray("compiler_settings_fingerprints").addObject().put("path", settings.toString()).put("sha256", hash);
        CompilerInputs.capture(request, temp); Files.writeString(settings, "languageVersion=2.4");
        assertEquals("inputs_changed", K2Analyzer.analyze(request).path("status").asText());
        assertThrows(CompilerInputs.ChangedException.class, () -> CompilerInputs.capture(request, temp));
    }
    @Test void pluginBytesFlagsAndOptionsAreCompilerInputsEvenWithRestoredAttributes() throws Exception {
        var request = request(); Path plugin = temp.resolve("plugin.jar"); Files.writeString(plugin, "aaaa");
        request.putArray("plugin_classpath").add(plugin.toString()); var first = CompilerInputs.capture(request, temp);
        var modified = Files.getLastModifiedTime(plugin); Files.writeString(plugin, "bbbb"); Files.setLastModifiedTime(plugin, modified);
        var changed = CompilerInputs.capture(request, temp); assertNotEquals(first.fingerprint(), changed.fingerprint());
        request.putArray("plugin_options").add("plugin:id:annotation=probe.Open");
        var options = CompilerInputs.capture(request, temp); assertNotEquals(changed.fingerprint(), options.fingerprint());
        request.putArray("compiler_arguments").add("-Xcontext-parameters"); assertNotEquals(options.fingerprint(), CompilerInputs.capture(request, temp).fingerprint());
        request.put("languageVersion", "2.3"); var language = CompilerInputs.capture(request, temp);
        request.put("apiVersion", "2.3"); assertNotEquals(language.fingerprint(), CompilerInputs.capture(request, temp).fingerprint());
    }
    @Test void friendModuleSelectionAndContentsInvalidateAnalysis() throws Exception {
        var request = request(); Path output = temp.resolve("friends"); Files.createDirectories(output);
        Path binary = output.resolve("Api.class"); Files.writeString(binary, "aaaa");
        var first = CompilerInputs.capture(request, temp);
        request.putArray("friend_paths").add(output.toString());
        var selected = CompilerInputs.capture(request, temp); assertNotEquals(first.fingerprint(), selected.fingerprint());
        Files.writeString(binary, "bbbb");
        assertNotEquals(selected.fingerprint(), CompilerInputs.capture(request, temp).fingerprint());
    }

    @Test void jdkImageChangesWithRestoredMtimeAndJdkSelectionChangeInvalidateInputs() throws Exception {
        var request = request(); Path jdk = temp.resolve("jdk"); Files.createDirectories(jdk.resolve("lib"));
        Files.writeString(jdk.resolve("release"), "JAVA_VERSION=17"); Files.writeString(jdk.resolve("lib/modules"), "aaaa");
        var first = CompilerInputs.capture(request, jdk); var time = Files.getLastModifiedTime(jdk.resolve("lib/modules"));
        Files.writeString(jdk.resolve("lib/modules"), "bbbb"); Files.setLastModifiedTime(jdk.resolve("lib/modules"), time);
        var changed = CompilerInputs.capture(request, jdk); assertNotEquals(first.fingerprint(), changed.fingerprint());
        assertEquals(changed, CompilerInputs.capture(request, jdk));
        Path other = temp.resolve("other-jdk"); Files.createDirectories(other.resolve("lib"));
        Files.copy(jdk.resolve("release"), other.resolve("release")); Files.copy(jdk.resolve("lib/modules"), other.resolve("lib/modules"));
        assertNotEquals(changed.fingerprint(), CompilerInputs.capture(request, other).fingerprint());
    }
    @Test void sourceRootsTrackKotlinFilesAndSymlinkedOutputsAndRejectCycles() throws Exception {
        var request = request(); Path root = temp.resolve("java"); Files.createDirectories(root);
        request.putArray("java_roots").add(root.toString()); var first = CompilerInputs.capture(request, temp);
        Files.writeString(root.resolve("Extra.kt"), "fun extra() = 1");
        assertNotEquals(first.fingerprint(), CompilerInputs.capture(request, temp).fingerprint());
        Path output = temp.resolve("output"); Files.createDirectories(output);
        try { Files.createSymbolicLink(output.resolve("linked"), root); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("Symlinks are unavailable");
        }
        request.putArray("classpath").add(output.toString()); var linked = CompilerInputs.capture(request, temp);
        Files.writeString(root.resolve("Extra.kt"), "fun extra() = 2");
        assertNotEquals(linked.fingerprint(), CompilerInputs.capture(request, temp).fingerprint());
        Files.createSymbolicLink(root.resolve("cycle"), output);
        assertThrows(java.io.IOException.class, () -> CompilerInputs.capture(request, temp));
    }
}
