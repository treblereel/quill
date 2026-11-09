package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.tools.ToolProvider;
import org.eclipse.jgit.api.Git;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.*;
import org.treblereel.mcp.db.*;
import org.treblereel.mcp.model.*;

/** Real javac output feeds the same scanners and persistence contracts as production. */
final class JavaEvidenceFixture {
    static Jdbi index(Path root, String source) throws Exception {
        return index(root, Map.of("src/main/java/p/Fixture.java",source));
    }
    /** The compiler settings the Maven integration records, so source declarations resolve. */
    static void writeCompilerSettings(Path root) throws Exception {
        var settings=new Properties();
        settings.setProperty("compilerSettingsProtocol","1");settings.setProperty("evidence","successful_mojo");
        settings.setProperty("sourceSet","main");settings.setProperty("release","17");
        settings.setProperty("jdkHome",System.getProperty("java.home"));
        var jdk=new Properties();try(var reader=Files.newBufferedReader(Path.of(System.getProperty("java.home"),"release"))){jdk.load(reader);}
        settings.setProperty("jdkVersion",jdk.getProperty("JAVA_VERSION").replace("\"",""));
        settings.setProperty("sourceRoot.count","1");settings.setProperty("sourceRoot.0",root.resolve("src/main/java").toString());
        settings.setProperty("compileClasspath.count","0");settings.setProperty("argument.count","0");
        settings.setProperty("buildInput.count","1");settings.setProperty("buildInput.path.0",root.resolve("pom.xml").toString());
        settings.setProperty("buildInput.sha256.0",SourceFileHashes.hash(root.resolve("pom.xml")));
        try(var writer=Files.newBufferedWriter(root.resolve("target/quill-java-main.properties"))){settings.store(writer,"fixture");}
    }
    static Jdbi index(Path root, Map<String,String> sources) throws Exception {
        List<Path> sourceFiles=new ArrayList<>();
        for(var entry:sources.entrySet()){Path file=root.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());if(file.toString().endsWith(".java"))sourceFiles.add(file);}
        var roots=List.of(root.resolve("src/main/java"),root.resolve("src/test/java")).stream().filter(Files::isDirectory).toList();
        Files.writeString(root.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>fixture</artifactId><version>1</version></project>");
        Files.writeString(root.resolve(".gitignore"),"target/\nindex.db*\n");
        String commit;
        try (Git git=Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern(".").call();
            commit=git.commit().setSign(false).setMessage("Fixture sources").setAuthor("Fixture","fixture@example.invalid").setCommitter("Fixture","fixture@example.invalid").call().getName();
        }
        Path output=Files.createDirectories(root.resolve("target/classes"));
        var compiler=ToolProvider.getSystemJavaCompiler();
        try (var manager=compiler.getStandardFileManager(null,null,null)) {
            assertTrue(compiler.getTask(null,manager,null,List.of("--release","17","-g","-proc:none","-d",output.toString()),
                    null,manager.getJavaFileObjectsFromPaths(sourceFiles)).call());
        }
        var snapshot=ClassFileSnapshot.capture(List.of(output));
        var scan=JandexScanner.scan(snapshot,roots);
        Map<String,Integer> ids=new HashMap<>();
        for (int i=0;i<scan.classes().size();i++) ids.put(scan.classes().get(i).className(),i+1);
        var bytecode=BytecodeDependencyScanner.analyze(snapshot,ids.keySet());
        var calls=bytecode.methodCalls().stream().filter(c -> ids.containsKey(c.toClass())).map(c ->
                new MethodCallRecord(ids.get(c.fromClass()),c.fromMethod(),c.fromDescriptor(),ids.get(c.toClass()),c.toMethod(),c.toDescriptor(),
                        c.invocationKind(),c.occurrences(),c.evidenceLines(),c.instructionOrdinals(),c.callerBranchCount(),c.callerExceptionHandlerCount(),
                        c.callerControlFlowEdges(),c.callerAsyncBoundaries(),c.callerExternalCalls())).toList();
        var fields=bytecode.fieldAccesses().stream().filter(c -> ids.containsKey(c.toClass())).map(c ->
                new FieldAccessRecord(ids.get(c.fromClass()),c.fromMethod(),c.fromDescriptor(),ids.get(c.toClass()),c.fieldName(),c.fieldDescriptor(),
                        c.accessKind(),c.occurrences(),c.evidenceLines(),c.instructionOrdinals())).toList();
        Jdbi db=QuillDatabase.create(root.resolve("index.db"));
        var classes=scan.classes().stream().map(c -> new ClassRecord(c.id(),c.className(),c.kind(),c.superclass(),c.interfaces(),c.sourceFile(),c.sourceLine(),
                c.isBean(),c.sourceTokens(),c.fileId(),c.origin(),c.lifecycle(),".",c.sourceFile()!=null && c.sourceFile().contains("src/test/java/")?"test":"main")).toList();
        IndexWriter.writeFresh(db, new IndexFixture()
                .classes(classes)
                .metadata(Map.of("index_id","fixture","indexed_at",Instant.now().toString(),"module_discovery_complete","true",
                        "project_root",root.toString(),"last_commit",commit,"missing_test_output_modules","[]","compiled_before_index","true",
                        "java_source_roots",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(roots.stream().map(root::relativize).map(Path::toString).toList()),"java_source_hashes",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                                SourceFileHashes.captureJava(root,roots))))
                .classMembers(JandexScanner.extractClassMembers(scan.index(),ids))
                .methodCalls(calls)
                .fieldAccesses(fields)
                .configuration(ConfigurationScanner.scan(root,List.of(root),scan.index(),ids,classes,snapshot).configuration()).snapshot());
        for(var entry:JvmApiScanner.capture(snapshot,ids).entrySet())
            db.useHandle(h->h.createUpdate("INSERT INTO jvm_api(class_id,evidence) VALUES (:id,:evidence)")
                    .bind("id",entry.getKey()).bind("evidence",entry.getValue()).execute());
        return db;
    }
}
