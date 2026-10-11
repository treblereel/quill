package org.treblereel.mcp.kotlin;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemanticWorkerTest {
    @TempDir Path temp;
    private JsonNode resolve(String source, String token) throws Exception {
        Path file = temp.resolve("Probe.kt"); Files.writeString(file, source);
        var request = new ObjectMapper().createObjectNode();
        request.put("file", file.toString()); request.put("offset", source.lastIndexOf(token));
        request.putArray("sources").add(file.toString());
        request.putArray("classpath").add(Path.of(kotlin.Unit.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return SemanticWorker.analyze(request);
    }
    @Test void resolvesTypedOverloadsReceiverAndGenericErasure() throws Exception {
        String declarations = "package probe\nfun pick(x: Int): Int = x\nfun pick(x: String): String = x\nfun <T> identity(x: T): T = x\nclass Receiver { fun pick(x: Long): Long = x }\n";
        assertEquals("(I)I", resolve(declarations + "fun run() = pick(1)", "pick(").path("descriptor").asText());
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;", resolve(declarations + "fun run() = pick(\"s\")", "pick(").path("descriptor").asText());
        var receiver = resolve(declarations + "fun run(r: Receiver) = r.pick(1L)", "pick(");
        assertEquals("probe.Receiver", receiver.path("owner").asText()); assertEquals("(J)J", receiver.path("descriptor").asText());
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", resolve(declarations + "fun run() = identity(\"s\")", "identity(").path("descriptor").asText());
    }
    @Test void localFunctionAndLambdaNeverResolveToIndexedTopLevelName() throws Exception {
        assertEquals("local", resolve("fun pick() = 1\nfun run() { val pick = { 2 }; pick() }", "pick()").path("status").asText());
        assertEquals("local", resolve("fun pick() = 1\nfun run() {\nfun pick() = 2\npick()\n}", "pick()").path("status").asText());
    }
    @Test void extensionDescriptorsIncludeReceiverAndNamesHonorJvmName() throws Exception {
        var result = resolve("package probe\nfun String.pick(x: Int) = x\nfun run() = \"s\".pick(1)", "pick(");
        assertEquals("(Ljava/lang/String;I)I", result.path("descriptor").asText(), result.toString());
        var renamed = resolve("package probe\n@JvmName(\"renamed\") fun pick(x: Int) = x\nfun run() = pick(1)", "pick(");
        assertEquals("renamed", renamed.path("name").asText());
    }
    @Test void errorsAndIncompleteSourceProduceNoSemanticAttestation() throws Exception {
        assertEquals("analysis_error", resolve("fun run() = missing(1)", "missing(").path("status").asText());
        assertEquals("analysis_error", resolve("fun pick() = 1\nfun run() { pick()", "pick()").path("status").asText());
        assertEquals("analysis_error", resolve("fun pick(x: Int) = x\nfun run() = pick(\"bad\")", "pick(").path("status").asText());
    }
    @Test void inheritedMemberResolvesToDeclaringOwner() throws Exception {
        var result = resolve("package probe\nopen class Base { fun pick(x: Int) = x }\nclass Child : Base()\nfun run(c: Child) = c.pick(1)", "pick(");
        assertEquals("probe.Base", result.path("owner").asText(), result.toString());
    }
}
