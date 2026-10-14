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
    @Test void foreignIndexCannotSupplyCompilerModuleEvidence() throws Exception {
        var db = QuillDatabase.create(root.resolve("foreign.db"));
        db.useHandle(handle -> {
            handle.execute("INSERT INTO metadata(key,value) VALUES ('project_root',?)", root.toRealPath().toString());
            handle.execute("INSERT INTO metadata(key,value) VALUES ('worktree_id','foreign')");
        });
        assertEquals("index_ownership_mismatch", JavaSemanticQueries.prepare(db, root, file, offset()).path("status").asText());
    }
    @Test void workerModelsIncludePhysicalProjectIdentity() throws Exception {
        var a = (com.fasterxml.jackson.databind.node.ObjectNode) JavaSemanticQueries.prepare(root, file, offset());
        var b = a.deepCopy().put("project_id", "other-checkout");
        assertTrue(a.has("worktree_id"));
        try (var client = new JavaWorkerClient()) {
            assertFalse(client.query(a).path("reused").asBoolean());
            assertFalse(client.query(b).path("reused").asBoolean());
            assertTrue(client.query(a).path("reused").asBoolean());
        }
    }
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
    private JsonNode gradleModule(String name) throws Exception {
        Path module = root.resolve(name);
        Path source = module.resolve("src/main/java/p/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package p; class Example { int pick(int x){return x;} void run(){pick(1);}} ");
        Path captured = module.resolve("build/quill-java-main.properties"); Files.createDirectories(captured.getParent());
        Properties values = new Properties(); values.putAll(settings);
        values.setProperty("evidence", "successful_task");
        values.setProperty("sourceRoot.0", source.getParent().getParent().toString());
        try (var writer = Files.newBufferedWriter(captured)) { values.store(writer, "test"); }
        return JavaSemanticQueries.prepare(root, source, Files.readString(source).indexOf("pick(1)"));
    }
    @Test void gradleSubprojectsWithoutBuildScriptsRetainTwoModelsAndEvictTheThird() throws Exception {
        JsonNode a = gradleModule("a"), b = gradleModule("b"), c = gradleModule("c");
        for (JsonNode request : List.of(a,b,c)) assertTrue(request.has("sources"), request.toString());
        try (var client = new JavaWorkerClient()) {
            assertFalse(client.query(a).path("reused").asBoolean());
            assertFalse(client.query(b).path("reused").asBoolean());
            assertTrue(client.query(a).path("reused").asBoolean(), "A survives A→B→A");
            assertFalse(client.query(c).path("reused").asBoolean());
            assertTrue(client.query(a).path("reused").asBoolean(), "A survives B eviction");
            assertFalse(client.query(b).path("reused").asBoolean(), "B was evicted by C");
            assertTrue(client.alive());
        }
    }
    @Test void indexedGradleSubprojectWithoutBuildScriptUsesItsOwnCapture() throws Exception {
        JsonNode expected = gradleModule("child");
        Path source = Path.of(expected.path("file").asText());
        var db = QuillDatabase.create(root.resolve("index.db"));
        db.useHandle(h -> h.execute("""
                INSERT INTO files(project_path,repository_path,kind,origin,lifecycle,module,source_set)
                VALUES(?,?,'java','source','current','child','main')
                """, root.relativize(source).toString(), root.relativize(source).toString()));
        JsonNode result = JavaSemanticQueries.resolve(db, root, source, expected.path("offset").asInt());
        assertEquals("resolved", result.path("status").asText(), result.toString());
        assertEquals("(I)I", result.path("descriptor").asText());
    }
    @Test void sourceAndCapturePathAliasesUseTheSameCompilerModelAndIndexedDeclaration() throws Exception {
        Path alias = root.resolve("project-alias"); Files.createSymbolicLink(alias, root.toRealPath());
        settings.setProperty("sourceRoot.0", root.resolve("src/main/java").toRealPath().toString());
        settings.setProperty("generatedSourcesDirectory", alias.resolve("target/not-created/generated").toString());
        settings.setProperty("compileClasspath.count", "1");
        Files.createDirectories(root.resolve("target/classes"));
        settings.setProperty("compileClasspath.0", alias.resolve("target/classes").toString()); save();
        Path aliasedFile = alias.resolve("src/main/java/p/Example.java");
        var prepared = JavaSemanticQueries.prepare(alias, aliasedFile, offset());
        assertTrue(prepared.has("sources"), prepared.toString());
        assertEquals(root.toRealPath().toString(), prepared.path("project_root").asText());
        assertEquals(root.toRealPath().resolve("target/not-created/generated").toString(), prepared.path("java_roots").get(1).asText());
        var db = QuillDatabase.create(root.resolve("alias-index.db"));
        db.useHandle(h -> {
            h.execute("INSERT INTO classes(id,class_name,kind,interfaces,source_file,source_line,origin,lifecycle) VALUES(1,'p.Example','CLASS','[]','src/main/java/p/Example.java',1,'source','current')");
            h.execute("INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) VALUES(1,'METHOD','pick','pick(int):int','(I)I','int','[\"int\"]','','[]')");
        });
        String line = Files.readString(file);
        var result = new ObjectMapper().readTree(new PositionSymbolQueries().getSymbolAtPosition(db,alias,"src/main/java/p/Example.java",1,line.indexOf("pick(1 )")+1));
        assertEquals("compiler",result.path("resolution_basis").asText(),result.toString());
        assertEquals("(I)I",result.path("selected").path("descriptor").asText());
        var refs = JavaSemanticQueries.resolve(db,alias,aliasedFile,-2);
        assertEquals("resolved",refs.path("status").asText()); assertEquals(1,refs.path("references").size());
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
        assertEquals(3,result.path("selected").path("location").path("line").asInt());
        assertEquals("compiler",result.path("selected").path("location").path("basis").asText());
        assertTrue(result.path("selected").path("location").path("column").asInt() > 0);
        assertEquals(3,result.path("semantic_analysis").path("source_end_line").asInt());
        assertEquals("javac",result.path("semantic_analysis").path("backend").asText()); assertTrue(result.has("_meta"));
    }
}
