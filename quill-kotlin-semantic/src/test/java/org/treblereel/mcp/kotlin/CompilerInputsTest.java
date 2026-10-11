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
