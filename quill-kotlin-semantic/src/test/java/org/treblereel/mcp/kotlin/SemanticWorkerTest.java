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
    @Test void sourceFacadesAndBinaryTopLevelFunctionsUsePhysicalOwners() throws Exception {
        var facade = resolve("@file:JvmName(\"Named\")\npackage probe\nfun pick() = 1\nfun run() = pick()", "pick()");
        assertEquals("probe.Named", facade.path("owner").asText());
        var part = resolve("@file:JvmName(\"Shared\")\n@file:JvmMultifileClass\npackage probe\nfun pick() = 1\nfun run() = pick()", "pick()");
        assertEquals("probe.Shared__ProbeKt", part.path("owner").asText(), part.toString());
        var binary = resolve("fun run() = listOf(1)", "listOf(");
        assertEquals("resolved", binary.path("status").asText(), binary.toString());
        assertTrue(binary.path("owner").asText().startsWith("kotlin.collections.CollectionsKt"));
        assertEquals("(Ljava/lang/Object;)Ljava/util/List;", binary.path("descriptor").asText());
        assertFalse(binary.has("source_file"));
    }

    @Test void typeReferencesNestedOwnersAndInvalidPositionsAreConservative() throws Exception {
        var type = resolve("package probe\nclass Outer { class Nested }\nfun run(x: Outer.Nested) = x", "Nested");
        assertEquals("CLASS", type.path("kind").asText(), type.toString());
        assertEquals("probe.Outer$Nested", type.path("owner").asText());
        var literal = resolve("fun pick(x: String) = x\nfun run() = pick(\"literal\")", "literal");
        assertEquals("unresolved", literal.path("status").asText(), literal.toString());
        assertEquals("unresolved", resolve("fun pick() = 1", "missing").path("status").asText());
    }

    @Test void k2ResolvesSmartCastThroughBooleanVariableAndContextParameters() throws Exception {
        var smart = resolve("package probe\nclass Receiver { fun pick() = 1 }\nfun run(x: Any) { val receiver = x is Receiver; if (receiver) x.pick() }", "pick()");
        assertEquals("resolved", smart.path("status").asText(), smart.toString());
        assertEquals("kotlin_k2_fir", smart.path("backend").asText());
        assertEquals("2.4", smart.path("language_version").asText());
        assertEquals("probe.Receiver", smart.path("owner").asText());
        var context = resolve("package probe\ncontext(prefix: String) fun pick(x: Int): String = prefix + x\nfun run() = with(\"s\") { pick(1) }", "pick(");
        assertEquals("resolved", context.path("status").asText(), context.toString());
        assertEquals("(Ljava/lang/String;I)Ljava/lang/String;", context.path("descriptor").asText());
    }

    @Test void jvmDescriptorsPreserveBoxingArraysBoundsAndSuspendReturns() throws Exception {
        assertEquals("(Ljava/lang/Integer;)Ljava/lang/Integer;", resolve("fun pick(x: Int?): Int? = x\nfun run() = pick(null)", "pick(").path("descriptor").asText());
        assertEquals("([Ljava/lang/Integer;)[Ljava/lang/Integer;", resolve("fun pick(x: Array<Int>): Array<Int> = x\nfun run() = pick(arrayOf(1))", "pick(").path("descriptor").asText());
        assertEquals("(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;", resolve("fun <T : CharSequence> pick(x: T): T = x\nfun run() = pick(\"s\")", "pick(").path("descriptor").asText());
        assertEquals("(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;", resolve("suspend fun pick(x: Int): String = x.toString()\nsuspend fun run() = pick(1)", "pick(").path("descriptor").asText());
        assertEquals("()V", resolve("fun pick() {}\nfun run() = pick()", "pick()").path("descriptor").asText());
        assertEquals("()Lkotlin/Unit;", resolve("fun pick(): Unit? = null\nfun run() = pick()", "pick()").path("descriptor").asText());
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", resolve("package probe\nopen class Base<T> { fun pick(x: T): T = x }\nclass Child : Base<String>()\nfun run(c: Child) = c.pick(\"s\")", "pick(").path("descriptor").asText());
    }

    @Test void constructorsPropertiesAndCallableReferencesResolve() throws Exception {
        var constructor = resolve("package probe\nclass Receiver(x: Int)\nfun run() = Receiver(1)", "Receiver(");
        assertEquals("CONSTRUCTOR", constructor.path("kind").asText(), constructor.toString());
        assertEquals("(I)V", constructor.path("descriptor").asText());
        var property = resolve("package probe\nclass Receiver { val name = \"s\" }\nfun run(r: Receiver) = r.name", "name");
        assertEquals("PROPERTY", property.path("kind").asText());
        assertEquals("probe.Receiver", property.path("owner").asText());
        assertEquals("(I)I", resolve("fun pick(x: Int) = x\nval ref: (Int) -> Int = ::pick", "pick").path("descriptor").asText());
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
