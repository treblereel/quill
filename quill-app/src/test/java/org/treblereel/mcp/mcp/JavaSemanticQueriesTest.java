package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class JavaSemanticQueriesTest {
    @TempDir Path root;
    Path file, capture;
    Properties settings;
    @BeforeEach void fixture() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        file = root.resolve("src/main/java/p/Example.java"); Files.createDirectories(file.getParent());
        Files.writeString(file, "package p; class Example {static int pick(int x){return x;} static int pick(String x){return 2;} void run(){pick(1 );}}");
        capture = root.resolve("target/quill-java-main.properties"); Files.createDirectories(capture.getParent());
        settings = new Properties();
        settings.setProperty("compilerSettingsProtocol", "1"); settings.setProperty("evidence", "successful_mojo");
        settings.setProperty("sourceSet", "main"); settings.setProperty("release", "17");
        settings.setProperty("jdkHome", System.getProperty("java.home"));
        var jdk = new Properties(); try (var reader = Files.newBufferedReader(Path.of(System.getProperty("java.home"), "release"))) { jdk.load(reader); }
        settings.setProperty("jdkVersion", jdk.getProperty("JAVA_VERSION").replace("\"", ""));
        settings.setProperty("sourceRoot.count", "1"); settings.setProperty("sourceRoot.0", root.resolve("src/main/java").toString());
        settings.setProperty("compileClasspath.count", "0"); settings.setProperty("argument.count", "0");
        settings.setProperty("buildInput.count", "1"); settings.setProperty("buildInput.path.0", root.resolve("pom.xml").toString());
        settings.setProperty("buildInput.sha256.0", JavaSemanticQueries.hash(root.resolve("pom.xml"))); save();
    }
    void save() throws Exception { try (var writer = Files.newBufferedWriter(capture)) { settings.store(writer, "test"); } }
    int offset() throws Exception { return Files.readString(file).indexOf("pick(1 )"); }
    JsonNode request() throws Exception { return JavaSemanticQueries.prepare(root, file, offset()); }
    String status() throws Exception { return request().path("status").asText(); }
    @AfterEach void stop() { JavaSemanticQueries.close(); }
    @Test void servesExactOverloadReusesModelAndRecoversKilledProcess() throws Exception {
        try (var client = new JavaWorkerClient()) {
            JsonNode first = client.query(request()); assertEquals("resolved", first.path("status").asText());
            assertEquals("(I)I", first.path("descriptor").asText()); assertFalse(first.path("reused").asBoolean());
            assertTrue(client.query(request()).path("reused").asBoolean());
            client.killForTest();
            // A racing process death may fail this request; the following request must recover.
            JsonNode recovered = client.query(request());
            if (!recovered.path("status").asText().equals("resolved")) recovered = client.query(request());
            assertEquals("resolved", recovered.path("status").asText()); assertFalse(recovered.path("reused").asBoolean());
            client.close(); assertFalse(client.alive());
        }
    }
    @Test void restoredTimestampEditCannotReusePreviousOverload() throws Exception {
        try (var client = new JavaWorkerClient()) {
            assertEquals("(I)I", client.query(request()).path("descriptor").asText());
            FileTime time = Files.getLastModifiedTime(file);
            Files.writeString(file, Files.readString(file).replace("pick(1 );", "pick(\"\");")); Files.setLastModifiedTime(file, time);
            JsonNode changed = client.query(JavaSemanticQueries.prepare(root, file, Files.readString(file).indexOf("pick(\"\")")));
            assertEquals("(Ljava/lang/String;)I", changed.path("descriptor").asText());
            assertEquals(2, changed.path("generation").asInt());
            Files.writeString(file, "package p; class Example {BROKEN}");
            assertEquals("analysis_errors", client.query(JavaSemanticQueries.prepare(root, file, 0)).path("status").asText());
        }
    }
    @Test void warmsThenWaitsForCompilerModel() throws Exception {
        try (var client = new JavaWorkerClient(); var coordinator = new KotlinWarmupCoordinator(client::query, Duration.ZERO)) {
            assertTrue(coordinator.prewarm(root.toString(), () -> JavaSemanticQueries.prepare(root, file, -1)));
            JsonNode result = coordinator.query(root.toString(), () -> {
                try { return request(); } catch (Exception e) { throw new RuntimeException(e); }
            }, () -> JavaSemanticQueries.prepare(root, file, -1));
            assertEquals("resolved", result.path("status").asText());
        }
    }
    @Test void compilerSettingsAndJdkFailuresAreExplicit() throws Exception {
        String[][] cases = {{"compilerSettingsProtocol","2","compiler_settings_protocol_unsupported"},
                {"evidence","configured_task","compiler_not_executed"}, {"compilerId","eclipse","compiler_unsupported"},
                {"enablePreview","true","preview_unsupported"}, {"encoding","ISO-8859-1","encoding_unsupported"},
                {"legacyCompilerArgument","-Xplugin:evil","compiler_arguments_unsupported"},
                {"release","n/a","release_settings_unsupported"}, {"jdkHome",root.toString(),"jdk_unavailable"},
                {"jdkVersion","99","jdk_changed"}};
        Properties original = new Properties(); original.putAll(settings);
        for (String[] item : cases) {
            settings.clear(); settings.putAll(original); settings.setProperty(item[0],item[1]); save();
            assertEquals(item[2],status(), item[0]);
        }
    }
    @Test void sourceTargetModeRetainsActualJdkApiRatherThanImposingRelease() throws Exception {
        settings.remove("release"); settings.setProperty("source", "17"); settings.setProperty("target", "17");
        settings.setProperty("jdkVersion", settings.getProperty("jdkVersion") + "+test-build"); save();
        assertFalse(request().has("release")); assertEquals("17",request().path("source").asText());
        try (var client = new JavaWorkerClient()) { assertEquals("resolved",client.query(request()).path("status").asText()); }
    }
    @Test void identicalRootsInDifferentSourceSetsRemainAmbiguous() throws Exception {
        Files.copy(capture,capture.resolveSibling("quill-java-test.properties"));
        assertEquals("source_set_ambiguous",status());
    }
    @Test void rejectsInjectedCompilerPluginsAndMissingClasspath() throws Exception {
        settings.setProperty("argument.count","1"); settings.setProperty("argument.0","-Xplugin:evil"); save();
        assertEquals("compiler_arguments_unsupported",status());
        settings.setProperty("argument.0","-parameters"); settings.setProperty("compileClasspath.count","1");
        settings.setProperty("compileClasspath.0",root.resolve("missing.jar").toString()); save();
        assertEquals("classpath_missing",status());
    }
    @Test void detectsStaleBuildAndMissingCaptureProtocolOrInputs() throws Exception {
        Files.writeString(root.resolve("pom.xml"),"changed"); assertEquals("compiler_settings_stale",status());
        settings.setProperty("buildInput.sha256.0",JavaSemanticQueries.hash(root.resolve("pom.xml"))); save();
        assertTrue(request().has("sources"));
        settings.remove("sourceRoot.count"); save(); assertEquals("compiler_settings_unavailable",status());
        settings.setProperty("sourceRoot.count","1"); settings.setProperty("buildInput.count","0"); save();
        assertEquals("compiler_build_inputs_missing",status());
    }
    @Test void customGradleSourceSetAndGeneratedRootsUseTheirCapturedSettings() throws Exception {
        Files.delete(root.resolve("pom.xml")); Files.writeString(root.resolve("build.gradle"),"plugins {}");
        Path output = root.resolve("build"); Files.createDirectories(output);
        capture = output.resolve("quill-java-integrationTest.properties");
        settings.setProperty("sourceSet","integrationTest"); settings.setProperty("evidence","successful_task");
        settings.setProperty("buildInput.path.0",root.resolve("build.gradle").toString());
        settings.setProperty("buildInput.sha256.0",JavaSemanticQueries.hash(root.resolve("build.gradle")));
        Path generated = root.resolve("build/generated"); Files.createDirectories(generated);
        Files.writeString(generated.resolve("Generated.java"),"package p; class Generated {}");
        settings.setProperty("generatedSourcesDirectory",generated.toString()); save();
        assertEquals("integrationTest",request().path("source_set").asText()); assertEquals(2,request().path("sources").size());
    }
    @Test void rejectsModuleDescriptorsAndSourcesEscapingThroughSymlinks() throws Exception {
        Path moduleInfo = file.getParent().resolve("module-info.java"); Files.writeString(moduleInfo,"module p {}");
        assertEquals("module_path_unsupported",status()); Files.delete(moduleInfo);
        Path outside = Files.createTempDirectory("java-outside-");
        try {
            Path target = Files.writeString(outside.resolve("Other.java"),"class Other {}");
            Files.createSymbolicLink(file.getParent().resolve("Other.java"),target);
            assertEquals("source_outside_root",status());
        } finally { Files.deleteIfExists(outside.resolve("Other.java")); Files.deleteIfExists(outside); }
    }
    @Test void timeoutsAndInterruptsDisposeWorker() throws Exception {
        try (var client = new JavaWorkerClient(Duration.ofMillis(1),"org.treblereel.mcp.javac.JavaWorker")) {
            assertEquals("timeout",client.query(request()).path("status").asText()); assertFalse(client.alive());
        }
        try (var client = new JavaWorkerClient()) {
            JsonNode prepared = request(); Thread.currentThread().interrupt();
            try { assertEquals("interrupted",client.query(prepared).path("status").asText()); assertTrue(Thread.currentThread().isInterrupted()); }
            finally { Thread.interrupted(); }
            assertFalse(client.alive());
        }
    }
    @Test void actualPositionQueryPreservesCrLfAndUtf16AndMatchesIndexedDescriptor() throws Exception {
        String source="package p;\r\nclass Example {\r\n static int pick(int x){return x;} static int pick(String x){return 2;}\r\n void run(){ String s=\"😀\"; pick(1); }\r\n}\r\n";
        Files.writeString(file,source);
        var db = QuillDatabase.create(root.resolve("index.db"));
        db.useHandle(h -> {
            int id=h.createUpdate("INSERT INTO classes(class_name,kind,interfaces,source_file,source_line,origin,lifecycle) VALUES ('p.Example','CLASS','[]','src/main/java/p/Example.java',2,'source','current')").executeAndReturnGeneratedKeys("id").mapTo(Integer.class).one();
            for(String descriptor:List.of("(I)I","(Ljava/lang/String;)I"))
                h.createUpdate("INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) VALUES (:id,'METHOD','pick',:descriptor,:descriptor,'int','[]','','[]')")
                    .bind("id",id).bind("descriptor",descriptor).execute();
        });
        String line=source.lines().toList().get(3);
        JsonNode result=new ObjectMapper().readTree(new PositionSymbolQueries().getSymbolAtPosition(db,root,"src/main/java/p/Example.java",4,line.indexOf("pick(1)")+1));
        assertEquals("resolved",result.path("resolution").asText());
        assertEquals("(I)I",result.path("selected").path("descriptor").asText());
        assertEquals("javac",result.path("semantic_analysis").path("backend").asText()); assertTrue(result.has("_meta"));
    }
}
