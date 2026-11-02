package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedBytecodeScannerTest {

    @TempDir Path root;

    @Test
    void generatedClassesNameTheApplicationClassAndTheirNonContainerReferences() throws Exception {
        Path sources = Files.createDirectories(root.resolve("gen-src/p"));
        Files.writeString(sources.resolve("Mapper.java"), "package p; public class Mapper {}");
        Files.writeString(sources.resolve("Mapper_ClientProxy.java"),
                "package p; public class Mapper_ClientProxy { Object creator = new dep.Creator(); Object target = new Mapper(); }");
        Path dep = Files.createDirectories(root.resolve("gen-src/dep"));
        Files.writeString(dep.resolve("Creator.java"), "package dep; public class Creator {}");
        Path classes = Files.createDirectories(root.resolve("gen-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                sources.resolve("Mapper.java").toString(), sources.resolve("Mapper_ClientProxy.java").toString(),
                dep.resolve("Creator.java").toString()));
        Files.createDirectories(root.resolve("target/classes/p"));
        Files.copy(classes.resolve("p/Mapper.class"), root.resolve("target/classes/p/Mapper.class"));
        Path jar = Files.createDirectories(root.resolve("target/quarkus-app/quarkus")).resolve("generated-bytecode.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entry : List.of("p/Mapper_ClientProxy.class", "dep/Creator.class")) {
                output.putNextEntry(new JarEntry(entry));
                Files.copy(classes.resolve(entry), output);
                output.closeEntry();
            }
        }

        var result = new ObjectMapper().readTree(GeneratedBytecodeScanner.scan(root, Set.of("p.Mapper")));

        assertEquals("target/quarkus-app/quarkus/generated-bytecode.jar", result.path("jars").get(0).path("path").asText());
        assertEquals(1, result.path("jars").get(0).path("application_classes").asInt());
        var generated = result.path("classes").get(0);
        assertEquals("p.Mapper_ClientProxy", generated.path("class").asText());
        assertEquals("p/Mapper_ClientProxy.class", generated.path("entry").asText());
        assertEquals("p.Mapper", generated.path("source_class").asText());
        assertEquals(List.of("dep.Creator", "p.Mapper"),
                generated.path("references").valueStream().map(node -> node.asText()).toList());
        assertEquals("p.Outer$Inner", GeneratedBytecodeScanner.sourceClass("p.Outer$Inner_Bean", Set.of("p.Outer$Inner", "p.Outer")));
        assertNull(GeneratedBytecodeScanner.sourceClass("p.Other_Bean", Set.of("p.Mapper")));
        assertNull(GeneratedBytecodeScanner.scan(root.resolve("target/classes"), Set.of("p.Mapper")));
    }
}
