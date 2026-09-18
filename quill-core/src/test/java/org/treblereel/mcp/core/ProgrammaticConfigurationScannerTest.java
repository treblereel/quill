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

        List<ConfigurationScanner.Usage> usages = ProgrammaticConfigurationScanner.scan(
                ClassFileSnapshot.capture(List.of(tempDir)), Map.of(className, 1),
                Map.of(className, cls)).configurationUsages();

        assertEquals(List.of("<dynamic>", "app.name", "feature.enabled", "service.timeout"),
                usages.stream().map(ConfigurationScanner.Usage::key).toList());
        assertEquals(1, usages.stream()
                .filter(usage -> usage.kind().equals("dynamic_config_key")).count());
        assertTrue(usages.stream().anyMatch(usage -> usage.annotation()
                .equals("java.lang.System#getProperty")));
        assertTrue(usages.stream().anyMatch(usage -> usage.annotation()
                .equals("org.springframework.core.env.Environment#getProperty")));
        assertTrue(usages.stream().anyMatch(usage -> usage.annotation()
                .equals("org.eclipse.microprofile.config.Config#getValue")));
        assertEquals(List.of("templates/order.html"),
                ProgrammaticConfigurationScanner.scan(
                        ClassFileSnapshot.capture(List.of(tempDir)), Map.of(className, 1),
                        Map.of(className, cls)).resourceUsages().stream()
                        .map(ConfigurationScanner.ResourceUsage::resourcePath).toList());
    }

    private static byte[] consumerBytecode() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/ConfigConsumer", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "read", "(Ljava/lang/String;Lorg/springframework/core/env/Environment;"
                        + "Lorg/eclipse/microprofile/config/Config;)V", null, null);
        method.visitCode();
        method.visitLdcInsn("app.name");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
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
        method.visitMaxs(3, 3);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
