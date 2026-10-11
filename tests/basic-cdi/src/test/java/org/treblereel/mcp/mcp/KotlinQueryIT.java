package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.command.ProjectIndexStore;
import org.treblereel.mcp.command.ProjectInitializer;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

@Tag("e2e")
class KotlinQueryIT {
    private static final String PACKAGE = "org.treblereel.mcp.fixture.kotlin.";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir static Path temp;
    static Path project;
    static Jdbi db;

    @BeforeAll static void compileAndIndex() throws Exception {
        Path repository = Path.of(System.getProperty("user.dir")).getParent().getParent();
        Path fixture = repository.resolve("tests/kotlin-maven");
        project = temp.resolve("fixture"); Files.createDirectories(project);
        Files.copy(fixture.resolve("pom.xml"), project.resolve("pom.xml"));
        try (var files = Files.walk(fixture.resolve("src"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path copy = project.resolve(fixture.relativize(file));
                Files.createDirectories(copy.getParent()); Files.copy(file, copy);
            }
        }
        Process compile = new ProcessBuilder(repository.resolve("mvnw").toString(), "-q", "-f",
                project.resolve("pom.xml").toString(), "compile").inheritIO().start();
        assertTrue(compile.waitFor(120, TimeUnit.SECONDS)); assertEquals(0, compile.exitValue());
        // A Java call uses @JvmStatic/@JvmOverloads forwarding instead of Kotlin's companion call.
        Path caller = project.resolve("Caller.java");
        Files.writeString(caller, "package " + PACKAGE.substring(0, PACKAGE.length()-1)
                + "; public class Caller { public static String invoke() { return StaticShapes.welcome(); } }");
        Process javac = new ProcessBuilder("javac", "-cp", project.resolve("target/classes").toString(),
                "-d", project.resolve("target/classes").toString(), caller.toString()).inheritIO().start();
        assertTrue(javac.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, javac.exitValue());
        // Inventory the Java caller so it participates in the application graph.
        Path source = project.resolve("src/main/java/org/treblereel/mcp/fixture/kotlin/Caller.java");
        Files.createDirectories(source.getParent()); Files.move(caller, source);
        assertTrue(ProjectInitializer.initialize(project, true));
        db = QuillDatabase.open(ProjectIndexStore.findDbForHead(project));
    }

    private JsonNode usages(String cls, String method, String kind, String descriptor) throws Exception {
        return JSON.readTree(new SymbolUsageQueries().findSymbolUsages(db, PACKAGE + cls,
                method, kind, descriptor, "all", 100, 0));
    }

    @Test void defaultCallsAndConstructorsIdentifyOriginalSourceCallers() throws Exception {
        var greeting = usages("BillingKt", "greeting", "method", null);
        assertTrue(greeting.path("usages").valueStream().anyMatch(u -> u.path("caller").path("method").asText().equals("defaultCaller")
                && u.path("normalized").asBoolean()), greeting.toPrettyString());
        assertTrue(greeting.path("usages").valueStream().anyMatch(u -> u.path("caller").path("method").asText().equals("explicitCaller")));
        var constructor = usages("Invoice", null, "constructor", "(Ljava/lang/String;Ljava/lang/String;)V");
        assertTrue(constructor.path("usages").valueStream().anyMatch(u -> u.path("caller").path("method").asText().equals("constructorCaller")), constructor.toPrettyString());
        assertFalse(constructor.path("usages").valueStream().anyMatch(u -> u.path("caller").path("descriptor").asText().contains("DefaultConstructorMarker")));
    }

    @Test void propertiesCombineGetterSetterAndSymbolIdRoundTrip() throws Exception {
        var property = usages("Invoice", "state", "property", null);
        assertTrue(property.path("usages").valueStream().anyMatch(u -> u.path("caller").path("method").asText().equals("propertyCaller") && u.path("usage_kind").asText().equals("read")), property.toPrettyString());
        assertTrue(property.path("usages").valueStream().anyMatch(u -> u.path("caller").path("method").asText().equals("propertyCaller") && u.path("usage_kind").asText().equals("write")));
        var byId = JSON.readTree(new SymbolUsageQueries().findSymbolUsages(db,
                property.path("symbol_id").asText(), null, null, null, "all", 100, 0));
        assertEquals(property.path("total"), byId.path("total"));
    }

    @Test void multifileAndJvmStaticOverloadsKeepCompilerPathEvidence() throws Exception {
        var multifile = usages("BillingApi__BillingValidationKt", "validateInvoice", "method", null);
        assertTrue(multifile.path("usages").valueStream().anyMatch(u -> u.path("caller").path("method").asText().equals("facadeCaller")
                && u.path("bytecode_path").size() == 2), multifile.toPrettyString());
        var companion = usages("StaticShapes$Companion", "welcome", "method", "(Ljava/lang/String;)Ljava/lang/String;");
        assertTrue(companion.path("usages").valueStream().anyMatch(u -> u.path("caller").path("class").asText().equals(PACKAGE + "Caller")
                && u.path("bytecode_path").size() >= 3), companion.toPrettyString());
    }

    @Test void shortenedLayoutAndExtensionDefaultsSurviveRealCompilerOutput() throws Exception {
        var root = IndexReader.findClassByName(db, PACKAGE + "RootInvoice").orElseThrow();
        assertTrue(root.sourceFile().endsWith("src/main/kotlin/RootInvoice.kt"));
        assertEquals("source", root.origin());
        var extension = usages("KotlinCallsKt", "describe", "method", null);
        assertTrue(extension.path("usages").valueStream().anyMatch(u -> u.path("caller").path("method").asText().equals("extensionCaller")), extension.toPrettyString());
        assertTrue(extension.path("extension").asBoolean());
    }
    @Test void compilerParameterMetadataSurvivesPersistenceAndDrivesNavigation() throws Exception {
        var owner = IndexReader.findClassByName(db, PACKAGE + "KotlinCallsKt").orElseThrow();
        var function = IndexReader.findKotlinDeclarations(db, owner.id()).stream()
                .filter(value -> value.name().equals("parameterShapes")).findFirst().orElseThrow();
        assertEquals(3, function.valueParameters().size());
        assertTrue(function.valueParameters().getFirst().hasDefault());
        assertTrue(function.valueParameters().get(1).vararg());
        assertEquals("required", function.valueParameters().get(2).name());
        var ctorOwner = IndexReader.findClassByName(db, PACKAGE + "ParameterShapes").orElseThrow();
        var constructor = IndexReader.findKotlinDeclarations(db, ctorOwner.id()).stream()
                .filter(value -> value.kind().equals("CONSTRUCTOR")).findFirst().orElseThrow();
        assertEquals(2, constructor.valueParameters().size());
        var childOwner = IndexReader.findClassByName(db, PACKAGE + "ParameterOuter$Child").orElseThrow();
        var childConstructor = IndexReader.findKotlinDeclarations(db, childOwner.id()).stream()
                .filter(value -> value.kind().equals("CONSTRUCTOR")).findFirst().orElseThrow();
        assertEquals(1, childConstructor.valueParameters().size());
        assertTrue(childConstructor.descriptor().contains("ParameterOuter;I"));
        Path live = project.resolve("src/main/kotlin/Live.kt");
        var query = new PositionSymbolQueries();
        String valid = "import " + PACKAGE + "parameterShapes\nfun run() = parameterShapes(required = \"ok\")";
        Files.writeString(live, valid);
        var result = JSON.readTree(query.getSymbolAtPosition(db, project, "src/main/kotlin/Live.kt", 2, 13));
        assertTrue(result.has("selected"), result.toPrettyString());
        Files.writeString(live, "import " + PACKAGE + "parameterShapes\nfun run() = parameterShapes(1)");
        assertFalse(JSON.readTree(query.getSymbolAtPosition(db, project, "src/main/kotlin/Live.kt", 2, 13)).has("selected"));
        Files.writeString(live, "import " + PACKAGE + "ParameterShapes\nfun run() = ParameterShapes(required = \"ok\")");
        var constructorResult = JSON.readTree(query.getSymbolAtPosition(db, project, "src/main/kotlin/Live.kt", 2, 13));
        assertEquals("CONSTRUCTOR", constructorResult.path("selected").path("kind").asText(), constructorResult.toPrettyString());
    }

    private JsonNode livePosition(String expression, String token) throws Exception {
        String source = "import " + PACKAGE + "*\nfun run() = " + expression;
        String path = "src/main/kotlin/Navigation.kt";
        Files.writeString(project.resolve(path), source);
        return JSON.readTree(new PositionSymbolQueries().getSymbolAtPosition(db, project, path,
                2, 13 + expression.indexOf(token)));
    }

    @Test void generatedJvmOverloadsNavigateToSingleSourceDeclaration() throws Exception {
        for (String arguments : java.util.List.of("required = \"ok\"", "1, \"ok\"", "1, \"ok\", \"?\"")) {
            var result = livePosition("overloadNavigation(" + arguments + ")", "overloadNavigation");
            assertEquals("resolved", result.path("resolution").asText(), result.toPrettyString());
            assertEquals("(ILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                    result.path("selected").path("jvm_descriptor").asText());
            assertEquals(1, result.path("candidate_count").asInt());
        }
        for (String arguments : java.util.List.of("", "1", "label = \"custom\"", "1, \"custom\"")) {
            var result = livePosition("OverloadConstructor(" + arguments + ")", "OverloadConstructor");
            assertEquals("resolved", result.path("resolution").asText(), result.toPrettyString());
            assertEquals("(ILjava/lang/String;)V", result.path("selected").path("jvm_descriptor").asText());
            assertEquals(1, result.path("candidates").valueStream()
                    .filter(value -> value.path("kind").asText().equals("CONSTRUCTOR")).count());
        }
        for (String arguments : java.util.List.of("", "\"custom\"")) {
            var result = livePosition("StaticShapes.welcome(" + arguments + ")", "welcome");
            assertEquals("resolved", result.path("resolution").asText(), result.toPrettyString());
            assertEquals(PACKAGE + "StaticShapes$Companion", result.path("selected").path("class_name").asText());
            assertEquals(1, result.path("candidate_count").asInt());
        }
    }

    @Test void realCompilerArgumentsSupportLambdasEscapedNamesAndOrderedMixing() throws Exception {
        for (String expression : java.util.List.of("argumentNavigation { \"ok\" }", "argumentNavigation(optional = 2, \"value\") { \"ok\" }",
                "argumentNavigation(`odd name` = \"value\") { \"ok\" }", "argumentNavigation(2, `odd name` = \"value\", block = { \"ok\" })")) {
            var result = livePosition(expression, "argumentNavigation");
            assertEquals("resolved", result.path("resolution").asText(), result.toPrettyString());
            assertTrue(result.path("selected").path("ranking_reasons").toString().contains("metadata_arguments_match"));
        }
        assertFalse(livePosition("argumentNavigation(`odd name` = \"value\", 2) { \"ok\" }", "argumentNavigation").has("selected"));
        assertFalse(livePosition("argumentNavigation(block = { \"ok\" }) { \"again\" }", "argumentNavigation").has("selected"));
    }

    @Test void semanticWorkerRefinesOverloadsReceiverAndLocalShadows() throws Exception {
        Path repository = Path.of(System.getProperty("user.dir")).getParent().getParent();
        Path worker = repository.resolve("quill-kotlin-semantic/target/quill-kotlin-semantic-0.1-SNAPSHOT-all.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(worker), "Build the kotlin-semantic profile to test the optional worker");
        Files.deleteIfExists(project.resolve("src/main/kotlin/Live.kt"));
        Files.deleteIfExists(project.resolve("src/main/kotlin/Navigation.kt"));
        String previous = System.getProperty("quill.kotlin.semantic.worker");
        System.setProperty("quill.kotlin.semantic.worker", worker.toString());
        try {
            var integer = livePosition("typedNavigation(1)", "typedNavigation");
            assertEquals("resolved", integer.path("resolution").asText(), integer.toPrettyString());
            assertEquals("high", integer.path("confidence").asText());
            assertEquals("(I)I", integer.path("selected").path("jvm_descriptor").asText());
            assertEquals("(Ljava/lang/String;)Ljava/lang/String;", livePosition("typedNavigation(\"s\")", "typedNavigation")
                    .path("selected").path("jvm_descriptor").asText());
            assertEquals(PACKAGE + "TypedReceiver", livePosition("TypedReceiver().typedNavigation(1L)", "typedNavigation")
                    .path("selected").path("class_name").asText());
            assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", livePosition("genericNavigation(\"s\")", "genericNavigation")
                    .path("selected").path("jvm_descriptor").asText());
            var local = livePosition("run { val typedNavigation = { 2 }; typedNavigation() }", "typedNavigation()");
            assertEquals("local_symbol", local.path("resolution").asText(), local.toPrettyString());
            assertFalse(local.has("selected"));
        } finally {
            if (previous == null) System.clearProperty("quill.kotlin.semantic.worker");
            else System.setProperty("quill.kotlin.semantic.worker", previous);
            Files.deleteIfExists(project.resolve("src/main/kotlin/Navigation.kt"));
        }
    }

    @Test void realCompilerInlineAndCoroutineLocationsSurviveIndexing() throws Exception {
        var greeting = usages("BillingKt", "greeting", "method", "(Ljava/lang/String;)Ljava/lang/String;");
        var inline = greeting.path("usages").valueStream().filter(value -> value.path("caller").path("method").asText().equals("debugInlineCaller"))
                .findFirst().orElseThrow();
        assertTrue(inline.path("source_locations").size() > 0, inline.toPrettyString());
        assertTrue(inline.path("source_locations").valueStream().anyMatch(value -> value.path("mapped").asBoolean()
                && value.path("source_file").asText().endsWith("KotlinCalls.kt")), inline.toPrettyString());
        var coroutine = IndexReader.findClassesByName(db, PACKAGE + "KotlinCallsKt$debugSuspendCaller$1").getFirst();
        var debug = IndexReader.findKotlinSourceDebug(db, coroutine.id()).orElseThrow();
        assertEquals("debugSuspendCaller", debug.coroutine().method());
        assertFalse(debug.coroutine().suspensionLines().isEmpty());
        assertTrue(debug.coroutine().supported());
    }

}
