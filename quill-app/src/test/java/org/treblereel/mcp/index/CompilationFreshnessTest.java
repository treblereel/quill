package org.treblereel.mcp.index;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompilationFreshnessTest {
    @TempDir Path root;

    @Test void successfulIncrementalCompileAcceptsUnchangedMainBytecodeAfterTestOnlyPomChange() throws Exception {
        fixture(root);
        Path output = root.resolve("target/classes/App.class");
        var modified = Files.getLastModifiedTime(output); byte[] bytes = Files.readAllBytes(output);
        assertEquals(CompiledOutputInspector.State.STALE, CompiledOutputInspector.inspect(root).state());
        confirm(root, "main", List.of("integration-tests"));
        assertEquals(CompiledOutputInspector.State.FRESH, CompiledOutputInspector.inspect(root).state());
        assertEquals(modified, Files.getLastModifiedTime(output)); assertArrayEquals(bytes, Files.readAllBytes(output));
    }

    @Test void sourceSetsAndProfilesRemainIndependentOfAnotherConsumerBuild() throws Exception {
        fixture(root); confirm(root, "main", List.of()); confirm(root, "test", List.of("integration-tests"));
        Path consumer = Files.createDirectories(root.resolve("consumer")); fixture(consumer); confirm(consumer, "main", List.of());
        assertEquals(CompiledOutputInspector.State.FRESH, CompiledOutputInspector.inspect(root).state());
        var tests = CompiledOutputInspector.compilation(root, "test");
        assertEquals("integration-tests", tests.path("active_profiles").get(0).asText());
        Path source = root.resolve("src/integration-test/java/PgIT.java"); var modified = Files.getLastModifiedTime(source);
        Files.writeString(source, "class PgIT { int x; }"); Files.setLastModifiedTime(source, modified);
        assertEquals(CompiledOutputInspector.State.FRESH, CompiledOutputInspector.inspect(root).state());
        assertEquals("changed", CompiledOutputInspector.compilation(root, "test").path("status").asText());
        assertEquals(List.of(root), ProjectLayout.staleTestOutputModules(List.of(
                new ProjectLayout.CompiledOutput(root.resolve("target/test-classes"), root, "test"))));
    }

    @Test void moduleSelectionDoesNotAcceptAnUnrelatedSuccessfulBuild() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><packaging>pom</packaging><modules><module>a</module><module>b</module></modules></project>");
        Path a = Files.createDirectories(root.resolve("a")), b = Files.createDirectories(root.resolve("b"));
        fixture(a); fixture(b); confirm(a, "main", List.of());
        Files.createDirectories(root.resolve(".quill"));
        Files.writeString(root.resolve(".quill/build-state.json"), "{\"successful\":true,\"finishedAt\":9999999999999}");
        var report = CompiledOutputInspector.inspect(root);
        assertEquals(CompiledOutputInspector.State.STALE, report.state()); assertEquals(List.of("b"), report.staleModules());
        assertEquals(CompiledOutputInspector.State.FRESH, CompiledOutputInspector.inspect(root, List.of("a")).state());
    }

    @Test void sourceAndOutputContentChangesCannotHideBehindPreservedTimestamps() throws Exception {
        fixture(root); legacyFresh(); confirm(root, "main", List.of());
        Path source = root.resolve("src/main/java/App.java"); var modified = Files.getLastModifiedTime(source);
        Files.writeString(source, "class App { int x; }"); Files.setLastModifiedTime(source, modified);
        assertEquals(CompiledOutputInspector.State.STALE, CompiledOutputInspector.inspect(root).state());
        confirm(root, "main", List.of());
        Path output = root.resolve("target/classes/App.class"); var compiled = Files.getLastModifiedTime(output);
        Files.write(output, new byte[]{9}); Files.setLastModifiedTime(output, compiled);
        assertEquals(CompiledOutputInspector.State.STALE, CompiledOutputInspector.inspect(root).state());
    }

    @Test void profileSwitchIsRecordedOnlyForTheSourceSetActuallyCompiled() throws Exception {
        fixture(root); confirm(root, "main", List.of("production")); confirm(root, "test", List.of("integration-tests"));
        confirm(root, "main", List.of());
        assertTrue(CompiledOutputInspector.compilation(root, "main").path("active_profiles").isEmpty());
        assertEquals("integration-tests", CompiledOutputInspector.compilation(root, "test").path("active_profiles").get(0).asText());
    }

    @Test void configuredTasksAndConcurrentEditsCannotConfirmCompilation() throws Exception {
        fixture(root); legacyFresh(); var values = confirm(root, "main", List.of());
        values.setProperty("evidence", "configured_task"); save(root, "main", values);
        assertEquals(CompiledOutputInspector.State.STALE, CompiledOutputInspector.inspect(root).state());
        values.setProperty("evidence", "successful_mojo"); values.setProperty("compilationEvidence", "inputs_changed_during_compile");
        save(root, "main", values);
        assertEquals(CompiledOutputInspector.State.STALE, CompiledOutputInspector.inspect(root).state());
    }

    @Test void copiedOrMalformedReceiptsCannotConfirmAnotherModule() throws Exception {
        fixture(root); legacyFresh(); var values = confirm(root, "main", List.of());
        Path other = Files.createDirectories(root.resolve("other")); values.setProperty("compilationModule", other.toString()); save(root, "main", values);
        assertEquals(CompiledOutputInspector.State.STALE, CompiledOutputInspector.inspect(root).state());
        values.setProperty("compilationModule", root.toString()); values.setProperty("compilationRoot.count", "-1"); save(root, "main", values);
        assertEquals(CompiledOutputInspector.State.STALE, CompiledOutputInspector.inspect(root).state());
    }

    @Test void mixedJvmSourcesRequireBothCompilerReceipts() throws Exception {
        fixture(root); legacyFresh(); var java = confirm(root,"main",List.of());
        java.setProperty("compilationLanguage.count","2"); java.setProperty("compilationLanguage.0","java"); java.setProperty("compilationLanguage.1","kotlin");
        save(root,"main",java);
        assertEquals(CompiledOutputInspector.State.STALE,CompiledOutputInspector.inspect(root).state());
        Path kotlin=root.resolve("target/quill-kotlin-main.properties");
        try(var writer=Files.newBufferedWriter(kotlin)){java.store(writer,"fixture");}
        assertEquals(CompiledOutputInspector.State.FRESH,CompiledOutputInspector.inspect(root).state());
        java.setProperty("compilationEvidence","awaiting_session_end");
        try(var writer=Files.newBufferedWriter(kotlin)){java.store(writer,"fixture");}
        assertEquals(CompiledOutputInspector.State.STALE,CompiledOutputInspector.inspect(root).state());
    }

    @Test void symbolicSourceRootsAreFingerprintedByContent() throws Exception {
        fixture(root);
        Path source=root.resolve("src/main/java"), actual=root.resolve("linked-java");
        Files.move(source,actual);
        Files.createSymbolicLink(source,actual);
        confirm(root,"main",List.of());
        assertEquals(CompiledOutputInspector.State.FRESH,CompiledOutputInspector.inspect(root).state());
        Path file=actual.resolve("App.java"); var time=Files.getLastModifiedTime(file);
        Files.writeString(file,"class App { int x; }"); Files.setLastModifiedTime(file,time);
        assertEquals(CompiledOutputInspector.State.STALE,CompiledOutputInspector.inspect(root).state());
    }

    @Test void testOutputsCannotCertifyTheMainOutputDirectory() throws Exception {
        fixture(root); legacyFresh(); var values=confirm(root,"main",List.of());
        Path test=root.resolve("target/test-classes");
        values.setProperty("compiledOutput.path",test.toString());values.setProperty("compiledOutput.sha256",JavaCompilerSettings.treeHash(test));
        save(root,"main",values);
        assertEquals(CompiledOutputInspector.State.STALE,CompiledOutputInspector.inspect(root).state());
    }

    private void legacyFresh() throws Exception {
        Files.setLastModifiedTime(root.resolve("pom.xml"),FileTime.fromMillis(1000));
        try(var files=Files.walk(root.resolve("src"))) {
            for(var file:files.filter(Files::isRegularFile).toList()) Files.setLastModifiedTime(file,FileTime.fromMillis(1000));
        }
        for(String name:List.of("target/classes/App.class","target/test-classes/PgIT.class"))
            Files.setLastModifiedTime(root.resolve(name),FileTime.fromMillis(2000));
        assertEquals(CompiledOutputInspector.State.FRESH,CompiledOutputInspector.inspect(root).state());
    }

    private void fixture(Path module) throws Exception {
        Files.writeString(module.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>app</artifactId><version>1</version></project>");
        for (String path : List.of("src/main/java/App.java", "src/integration-test/java/PgIT.java", "src/main/resources/data.txt")) {
            Path file = module.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, path.endsWith(".java") ? "class App {}" : "resource");
        }
        for (String path : List.of("target/classes/App.class", "target/test-classes/PgIT.class")) {
            Path file = module.resolve(path); Files.createDirectories(file.getParent()); Files.write(file, new byte[]{1});
            Files.setLastModifiedTime(file, FileTime.fromMillis(1000));
        }
    }

    private Properties confirm(Path module, String set, List<String> profiles) throws Exception {
        var values = new Properties(); values.setProperty("compilerSettingsProtocol", "1");
        values.setProperty("evidence", "successful_mojo"); values.setProperty("sourceSet", set);
        values.setProperty("compilationProtocol", "1"); values.setProperty("compilationEvidence", "matching");
        values.setProperty("compilationModule", module.toString());
        var inputs = new ArrayList<Path>(List.of(module.resolve("pom.xml")));
        if (!module.equals(root)) inputs.add(root.resolve("pom.xml"));
        values.setProperty("buildInput.count", Integer.toString(inputs.size()));
        for (int i=0;i<inputs.size();i++) {
            values.setProperty("buildInput.path."+i, inputs.get(i).toString());
            values.setProperty("buildInput.sha256."+i, JavaCompilerSettings.treeHash(inputs.get(i)));
        }
        var roots = new ArrayList<>(List.of(module.resolve("src/main/java"), module.resolve("src/main/resources")));
        if (set.equals("test")) { roots.add(module.resolve("src/integration-test/java")); roots.add(module.resolve("target/classes")); }
        values.setProperty("compilationRoot.count", Integer.toString(roots.size()));
        for (int i=0;i<roots.size();i++) {
            values.setProperty("compilationRoot.path."+i, roots.get(i).toString());
            values.setProperty("compilationRoot.sha256."+i, JavaCompilerSettings.treeHash(roots.get(i)));
        }
        values.setProperty("activeProfile.count", Integer.toString(profiles.size()));
        for (int i=0;i<profiles.size();i++) values.setProperty("activeProfile."+i, profiles.get(i));
        Path output=module.resolve(set.equals("main") ? "target/classes" : "target/test-classes");
        values.setProperty("compiledOutput.path", output.toString()); values.setProperty("compiledOutput.sha256", JavaCompilerSettings.treeHash(output));
        save(module,set,values); return values;
    }

    private void save(Path module, String set, Properties values) throws Exception {
        Path file=module.resolve("target/quill-java-"+set+".properties"); Files.createDirectories(file.getParent());
        try(var writer=Files.newBufferedWriter(file)){ values.store(writer,"fixture"); }
    }
}
