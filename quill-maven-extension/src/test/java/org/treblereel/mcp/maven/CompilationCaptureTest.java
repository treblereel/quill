package org.treblereel.mcp.maven;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.apache.maven.execution.*;
import org.apache.maven.model.*;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompilationCaptureTest {
    @TempDir Path root;

    @Test void noOpMainCompileRecordsProfilesWithoutRewritingBytecode() throws Exception {
        var event=event("compile"); var output=root.resolve("target/classes/App.class");
        var modified=Files.getLastModifiedTime(output); byte[] bytes=Files.readAllBytes(output);
        CompilationCapture.started(event); var values=new Properties(); CompilationCapture.finish(event,values); CompilationCapture.complete(true);
        assertEquals("matching",values.getProperty("compilationEvidence"));
        assertEquals("main",values.getProperty("sourceSet"));
        assertEquals("integration-tests",values.getProperty("activeProfile.0"));
        assertEquals(modified,Files.getLastModifiedTime(output)); assertArrayEquals(bytes,Files.readAllBytes(output));
    }

    @Test void concurrentSourceEditAndFailedExecutionsCannotProduceMatchingEvidence() throws Exception {
        var event=event("compile"); CompilationCapture.started(event);
        var source=root.resolve("src/main/java/App.java"); var modified=Files.getLastModifiedTime(source);
        Files.writeString(source,"class App { int x; }"); Files.setLastModifiedTime(source,modified);
        var values=new Properties(); CompilationCapture.finish(event,values); CompilationCapture.complete(true);
        assertEquals("inputs_changed_or_partial_compile",values.getProperty("compilationEvidence"));
        CompilationCapture.started(event); CompilationCapture.failed(event);
        CompilationCapture.finish(event,values); CompilationCapture.complete(true);
        assertEquals("inputs_changed_or_partial_compile",values.getProperty("compilationEvidence"));
    }

    @Test void testCompileIncludesMainOutputAndActualProfileTestSourceRoots() throws Exception {
        var event=event("testCompile"); CompilationCapture.started(event);
        Files.write(root.resolve("target/classes/App.class"),new byte[]{2});
        var values=new Properties(); CompilationCapture.finish(event,values); CompilationCapture.complete(true);
        assertEquals("inputs_changed_or_partial_compile",values.getProperty("compilationEvidence"));
        CompilationCapture.started(event); CompilationCapture.finish(event,values); CompilationCapture.complete(true);
        assertEquals("matching",values.getProperty("compilationEvidence"));
        assertEquals("test",values.getProperty("sourceSet"));
        assertTrue(values.values().contains(root.resolve("src/integration-test/java").toString()));
    }

    @Test void repeatedNoOpCaptureDoesNotRewriteTheEvidenceFile() throws Exception {
        var event=event("compile"); CompilationCapture.started(event); var values=new Properties(); CompilationCapture.finish(event,values); CompilationCapture.complete(true);
        Path file=root.resolve("target/capture.properties"); KotlinCompilerCapture.write(file,values);
        var modified=Files.getLastModifiedTime(file); byte[] bytes=Files.readAllBytes(file);
        values.setProperty("compiledAt","9999999999999"); KotlinCompilerCapture.write(file,values);
        assertEquals(modified,Files.getLastModifiedTime(file)); assertArrayEquals(bytes,Files.readAllBytes(file));
    }

    @Test void reactorProjectSwitchDoesNotChangeTheCapturedModule() throws Exception {
        var event=event("compile"); CompilationCapture.started(event); var values=new Properties(); CompilationCapture.finish(event,values);
        event.getSession().setCurrentProject(null);
        CompilationCapture.complete(true);
        assertEquals("matching",values.getProperty("compilationEvidence"));
        assertEquals(root.toString(),values.getProperty("compilationModule"));
        assertTrue(Files.isRegularFile(root.resolve("target/quill-java-main.properties")));
    }

    @Test void explicitCompilerSkipDoesNotClaimFreshOutput() throws Exception {
        var event=event("testCompile"); event.getSession().getUserProperties().setProperty("maven.test.skip","true");
        assertTrue(CompilationCapture.skipped(event));
    }

    private ExecutionEvent event(String goal) throws Exception {
        Files.writeString(root.resolve("pom.xml"),"<project/>");
        Path source=root.resolve("src/main/java/App.java"); Files.createDirectories(source.getParent()); Files.writeString(source,"class App {}");
        Path output=root.resolve("target/classes/App.class"); Files.createDirectories(output.getParent()); Files.write(output,new byte[]{1});
        var model=new Model(); model.setGroupId("t");model.setArtifactId("app");model.setVersion("1");
        var build=new Build(); build.setOutputDirectory(root.resolve("target/classes").toString());
        build.setTestOutputDirectory(root.resolve("target/test-classes").toString());model.setBuild(build);
        var project=new MavenProject(model);project.setFile(root.resolve("pom.xml").toFile());
        project.addCompileSourceRoot(source.getParent().toString());project.addTestCompileSourceRoot(root.resolve("src/integration-test/java").toString());
        var profile=new Profile();profile.setId("integration-tests");project.setActiveProfiles(List.of(profile));
        var plugin=new Plugin();plugin.setGroupId("org.apache.maven.plugins");plugin.setArtifactId("maven-compiler-plugin");plugin.setVersion("3.15.0");
        var mojo=new MojoExecution(plugin,goal,"default-"+goal);
        var request=new DefaultMavenExecutionRequest();request.setMultiModuleProjectDirectory(root.toFile());
        var session=new MavenSession(null,request,new DefaultMavenExecutionResult(),project);
        session.setCurrentProject(project);
        return new ExecutionEvent() {
            public Type getType(){return Type.MojoStarted;}
            public MavenSession getSession(){return session;}
            public MavenProject getProject(){return session.getCurrentProject();}
            public MojoExecution getMojoExecution(){return mojo;}
            public Exception getException(){return null;}
        };
    }
}
