package org.treblereel.mcp.command;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.workspace.*;
class SubmoduleWorkspaceTest {
    @TempDir Path root;
    void commit(Git git) throws Exception {git.add().addFilepattern(".").call();git.commit().setSign(false).setMessage("fixture").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call();}
    @Test void discoversGitlinksBeyondDirectoryDepthAndIndexesRepositoriesSeparately() throws Exception {
        Path source=Files.createDirectories(root.resolve("source")),workspace=Files.createDirectories(root.resolve("workspace")),parent=Files.createDirectories(workspace.resolve("app"));
        try(Git original=Git.init().setDirectory(source.toFile()).call();Git git=Git.init().setDirectory(parent.toFile()).call()) {
            Files.writeString(source.resolve("README"),"library");commit(original);Files.writeString(parent.resolve("README"),"parent");commit(git);
            try(var ignored=git.submoduleAdd().setPath("vendor/deep/library").setURI(source.toUri().toString()).call()){}commit(git);
            Path child=parent.resolve("vendor/deep/library");
            var discovery=WorkspaceDiscovery.discover(new WorkspaceManifest(1,workspace,1,List.of(".git",".quill"),"fixture"));
            assertEquals(Set.of(parent,child),new HashSet<>(discovery.repositories().stream().map(WorkspaceDiscovery.Repository::root).toList()));
            Path parentOut=Files.createDirectories(parent.resolve("target/classes")),childOut=Files.createDirectories(child.resolve("target/classes"));
            var own=new ProjectLayout.CompiledOutput(parentOut,parent,"main");var nested=new ProjectLayout.CompiledOutput(childOut,child,"main");
            assertEquals(List.of(own),ProjectInitializer.independentOutputs(parent,List.of(own,nested)));
            assertEquals(List.of(nested),ProjectInitializer.independentOutputs(child,List.of(nested)));
            for(Path project:List.of(parent,child)) {
                String name=project.equals(parent)?"Parent":"Library";
                Files.writeString(project.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>"+name.toLowerCase()+"</artifactId><version>1</version></project>");
                Files.writeString(project.resolve(".gitignore"),"target/\n.quill/\n");
                Path sourceFile=project.resolve("src/main/java/p/"+name+".java");Files.createDirectories(sourceFile.getParent());Files.writeString(sourceFile,"package p; public class "+name+" {}");
                assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","17","-d",project.resolve("target/classes").toString(),sourceFile.toString()));
                try(Git repository=Git.open(project.toFile())){commit(repository);}
                assertTrue(ProjectInitializer.initialize(project,true));
                var db=org.treblereel.mcp.db.QuillDatabase.open(ProjectIndexStore.findBestAvailableDb(project));
                var classes=org.treblereel.mcp.db.IndexReader.findAllClasses(db);
                assertEquals(List.of("p."+name),classes.stream().map(org.treblereel.mcp.model.ClassRecord::className).toList());
                org.treblereel.mcp.db.QuillDatabase.retire(db);
            }
            assertEquals(0,new picocli.CommandLine(new org.treblereel.mcp.QuillTopCommand()).execute("workspace","init","--project",workspace.toString(),"--index-only"));
            assertEquals(0,new picocli.CommandLine(new org.treblereel.mcp.QuillTopCommand()).execute("workspace","refresh","--project",workspace.toString(),"--index-only"));
            Path changed=child.resolve("src/main/java/p/Library.java");Files.writeString(changed,"package p; public class Library {public long updated;} ");
            assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","17","-d",childOut.toString(),changed.toString()));
            try(Git repository=Git.open(child.toFile())){commit(repository);}
            UpdateCommand.updateAfterSuccessfulBuild(child);
            var updated=org.treblereel.mcp.db.QuillDatabase.open(ProjectIndexStore.findBestAvailableDb(child));
            var cls=org.treblereel.mcp.db.IndexReader.findClassByName(updated,"p.Library").orElseThrow();
            assertTrue(org.treblereel.mcp.db.IndexReader.findClassMembers(updated,cls.id()).stream().anyMatch(m->m.name().equals("updated")));
            assertEquals(org.treblereel.mcp.core.GitAnalyzer.resolveHead(child),org.treblereel.mcp.db.IndexReader.getMetadata(updated).get("last_commit"));
            org.treblereel.mcp.db.QuillDatabase.retire(updated);
            Files.move(child.resolve(".git"),child.resolve(".git.saved"));
            var missing=WorkspaceDiscovery.discover(new WorkspaceManifest(1,workspace,1,List.of(".git",".quill"),"fixture"));
            assertTrue(missing.diagnostics().stream().anyMatch(s->s.contains("uninitialized")));
        }
    }
}
