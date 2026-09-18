package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.treblereel.mcp.model.ClassRecord;

class ProgrammaticConfigurationScannerTest {

    @TempDir Path tempDir;

    @Test
    void findsConstantAndDynamicKeysAcrossSupportedApis() throws Exception {
        String className = "example.ConfigConsumer";
        Path classFile = tempDir.resolve("example/ConfigConsumer.class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, consumerBytecode());
        ClassRecord cls = new ClassRecord(0, className, "CLASS", "java.lang.Object", List.of(),
                "src/main/java/example/ConfigConsumer.java", 1, false, 20,
                null, "source", "current", ".", "main");
        ClassRecord customEnvironment = new ClassRecord(0, "example.CustomEnvironment", "CLASS",
                "java.lang.Object", List.of("org.springframework.core.env.Environment"),
                "src/main/java/example/CustomEnvironment.java", 1, false, 20);
        Map<String, ClassRecord> indexedClasses = Map.of(
                className, cls, customEnvironment.className(), customEnvironment);

        List<ConfigurationScanner.Usage> usages = ProgrammaticConfigurationScanner.scan(
                ClassFileSnapshot.capture(List.of(tempDir)), Map.of(className, 1),
                indexedClasses).configurationUsages();

        assertEquals(List.of("<dynamic>", "app.name", "custom.mode", "feature.enabled",
                        "local.mode", "service.timeout", "static.mode"),
                usages.stream().map(ConfigurationScanner.Usage::key).toList());
        assertEquals(1, usages.stream()
                .filter(usage -> usage.kind().equals("dynamic_config_key")).count());
        assertTrue(usages.stream().anyMatch(usage -> usage.annotation()
                .equals("java.lang.System#getProperty")));
        assertTrue(usages.stream().anyMatch(usage -> usage.annotation()
                .equals("org.springframework.core.env.Environment#getProperty")));
        assertTrue(usages.stream().anyMatch(usage -> usage.annotation()
                .equals("org.eclipse.microprofile.config.Config#getValue")));
        var resources = ProgrammaticConfigurationScanner.scan(
                        ClassFileSnapshot.capture(List.of(tempDir)), Map.of(className, 1),
                        indexedClasses).resourceUsages();
        assertEquals(List.of("example/relative.txt", "file:/tmp/order.html",
                        "messages.properties", "templates/order.html"),
                resources.stream().map(ConfigurationScanner.ResourceUsage::resourcePath).toList());
        assertEquals("external_resource", resources.stream()
                .filter(resource -> resource.resourcePath().startsWith("file:"))
                .findFirst().orElseThrow().kind());
        assertEquals("resource_bundle", resources.stream()
                .filter(resource -> resource.resourcePath().equals("messages.properties"))
                .findFirst().orElseThrow().kind());
    }

    private static byte[] consumerBytecode() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/ConfigConsumer", null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "MODE_KEY", Type.getDescriptor(String.class), null, "static.mode").visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "read", "(Ljava/lang/String;Lorg/springframework/core/env/Environment;"
                        + "Lorg/eclipse/microprofile/config/Config;)V", null, null);
        method.visitCode();
        method.visitLdcInsn("local.mode");
        method.visitVarInsn(Opcodes.ASTORE, 3);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitLdcInsn("custom.mode");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "example/CustomEnvironment", "getProperty",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        method.visitInsn(Opcodes.POP);
        method.visitFieldInsn(Opcodes.GETSTATIC, "example/ConfigConsumer", "MODE_KEY",
                Type.getDescriptor(String.class));
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn("app.name");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn(org.objectweb.asm.Type.getObjectType("example/ConfigConsumer"));
        method.visitLdcInsn("relative.txt");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getResource",
                "(Ljava/lang/String;)Ljava/net/URL;", false);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitLdcInsn("file:/tmp/order.html");
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "org/springframework/core/io/ResourceLoader", "getResource",
                "(Ljava/lang/String;)Lorg/springframework/core/io/Resource;", true);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn("messages");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/ResourceBundle", "getBundle",
                "(Ljava/lang/String;)Ljava/util/ResourceBundle;", false);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitLdcInsn("service.timeout");
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "org/springframework/core/env/Environment", "getProperty",
                "(Ljava/lang/String;)Ljava/lang/String;", true);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitLdcInsn("feature.enabled");
        method.visitLdcInsn(org.objectweb.asm.Type.getType(Boolean.class));
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "org/eclipse/microprofile/config/Config", "getValue",
                "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", true);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn(org.objectweb.asm.Type.getObjectType("example/ConfigConsumer"));
        method.visitLdcInsn("/templates/order.html");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getResource",
                "(Ljava/lang/String;)Ljava/net/URL;", false);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(3, 4);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
