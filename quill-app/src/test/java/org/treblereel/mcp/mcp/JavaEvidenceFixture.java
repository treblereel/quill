package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.tools.ToolProvider;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.*;
import org.treblereel.mcp.db.*;
import org.treblereel.mcp.model.*;

/** Real javac output feeds the same scanners and persistence contracts as production. */
final class JavaEvidenceFixture {
    static Jdbi index(Path root, String source) throws Exception {
        Path file=root.resolve("src/p/Fixture.java"); Files.createDirectories(file.getParent()); Files.writeString(file,source);
        Path output=Files.createDirectories(root.resolve("classes"));
        var compiler=ToolProvider.getSystemJavaCompiler();
        try (var manager=compiler.getStandardFileManager(null,null,null)) {
            assertTrue(compiler.getTask(null,manager,null,List.of("--release","17","-g","-proc:none","-d",output.toString()),
                    null,manager.getJavaFileObjects(file)).call());
        }
        var snapshot=ClassFileSnapshot.capture(List.of(output));
        var scan=JandexScanner.scan(snapshot,List.of(root.resolve("src")));
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
        IndexWriter.writeFresh(db,scan.classes(),List.of(),List.of(),List.of(),
                Map.of("index_id","fixture","indexed_at",Instant.now().toString(),"module_discovery_complete","true"),
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
                JandexScanner.extractClassMembers(scan.index(),ids),calls,fields);
        return db;
    }
}
