package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BytecodeDependencyScannerTest {

    @TempDir Path tempDir;

    @Test
    void detectsConstructorOnlyDependencyWithoutDoubleCountingInit() throws URISyntaxException {
        Path testClasses = Path.of(BytecodeDependencyScannerTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());

        List<BytecodeDependencyScanner.StaticDependency> dependencies =
                BytecodeDependencyScanner.scan(List.of(testClasses));

        String consumer = Consumer.class.getName();
        String constructed = Constructed.class.getName();
        var matches = dependencies.stream()
                .filter(dependency -> dependency.fromClass().equals(consumer))
                .filter(dependency -> dependency.toClass().equals(constructed))
                .filter(dependency -> dependency.kind().equals("CONSTRUCTS"))
                .toList();

        assertEquals(1, matches.size());
        assertEquals(1, matches.get(0).occurrences());
        assertEquals(1, matches.get(0).evidenceLines().size());
        assertNotEquals(0, matches.get(0).evidenceLines().getFirst());
    }

    @Test
    void recordsMethodLevelCallerAndCalleeEvidence() throws URISyntaxException {
        Path testClasses = Path.of(BytecodeDependencyScannerTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());

        BytecodeDependencyScanner.ScanResult result = BytecodeDependencyScanner.analyze(
                ClassFileSnapshot.capture(List.of(testClasses)));

        assertEquals(1, result.methodCalls().stream()
                .filter(call -> call.fromClass().equals(Consumer.class.getName()))
                .filter(call -> call.fromMethod().equals("create"))
                .filter(call -> call.toClass().equals(Constructed.class.getName()))
                .filter(call -> call.toMethod().equals("<init>"))
                .filter(call -> call.invocationKind().equals("special"))
                .filter(call -> !call.evidenceLines().isEmpty())
                .filter(call -> !call.instructionOrdinals().isEmpty())
                .count());

        assertEquals(1, result.methodCalls().stream()
                .filter(call -> call.fromClass().equals(Consumer.class.getName()))
                .filter(call -> call.fromMethod().equals("conditionalCreate"))
                .filter(call -> call.toClass().equals(BranchConstructed.class.getName()))
                .filter(call -> call.toMethod().equals("<init>"))
                .filter(call -> call.callerBranchCount() > 0)
                .count());
    }

    @Test
    void recordsExactFieldReadsAndWrites() throws URISyntaxException {
        Path testClasses = Path.of(BytecodeDependencyScannerTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());

        BytecodeDependencyScanner.ScanResult result = BytecodeDependencyScanner.analyze(
                ClassFileSnapshot.capture(List.of(testClasses)));

        assertEquals(1, result.fieldAccesses().stream()
                .filter(access -> access.fromClass().equals(Consumer.class.getName()))
                .filter(access -> access.fromMethod().equals("setValue"))
                .filter(access -> access.toClass().equals(Consumer.class.getName()))
                .filter(access -> access.fieldName().equals("value"))
                .filter(access -> access.fieldDescriptor().equals("I"))
                .filter(access -> access.accessKind().equals("write_instance"))
                .filter(access -> !access.evidenceLines().isEmpty())
                .filter(access -> !access.instructionOrdinals().isEmpty())
                .count());
        assertEquals(1, result.fieldAccesses().stream()
                .filter(access -> access.fromClass().equals(Consumer.class.getName()))
                .filter(access -> access.fromMethod().equals("getValue"))
                .filter(access -> access.fieldName().equals("value"))
                .filter(access -> access.accessKind().equals("read_instance"))
                .filter(access -> !access.instructionOrdinals().isEmpty())
                .count());
    }

    @Test
    void snapshotSupportsAllIndexingPassesAfterClassFilesDisappear() throws Exception {
        copyClass(Consumer.class);
        copyClass(Constructed.class);
        ClassFileSnapshot snapshot = ClassFileSnapshot.capture(List.of(tempDir));
        String originalFingerprint = snapshot.fingerprint();

        try (var files = Files.walk(tempDir)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                Files.delete(file);
            }
        }

        assertNotNull(JandexScanner.scan(snapshot, List.of()).index()
                .getClassByName(Consumer.class.getName()));
        assertEquals(1, BytecodeDependencyScanner.scan(snapshot).stream()
                .filter(dependency -> dependency.fromClass().equals(Consumer.class.getName()))
                .filter(dependency -> dependency.toClass().equals(Constructed.class.getName()))
                .filter(dependency -> dependency.kind().equals("CONSTRUCTS"))
                .count());

        copyClass(Consumer.class);
        assertNotEquals(originalFingerprint,
                ClassFileSnapshot.capture(List.of(tempDir)).fingerprint());
    }

    @Test
    void detectsDirectServiceLoaderConsumer() throws URISyntaxException {
        Path testClasses = Path.of(BytecodeDependencyScannerTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());

        List<BytecodeDependencyScanner.StaticDependency> dependencies =
                BytecodeDependencyScanner.scan(List.of(testClasses));

        assertEquals(1, dependencies.stream()
                .filter(dependency -> dependency.fromClass()
                        .equals(ServiceConsumer.class.getName()))
                .filter(dependency -> dependency.toClass()
                        .equals(ServiceContract.class.getName()))
                .filter(dependency -> dependency.kind().equals("SERVICE_CONSUMES"))
                .count());
        assertEquals(1, dependencies.stream()
                .filter(dependency -> dependency.fromClass()
                        .equals(ServiceConsumer.class.getName()))
                .filter(dependency -> dependency.toClass().equals(Runnable.class.getName()))
                .filter(dependency -> dependency.kind().equals("SERVICE_CONSUMES"))
                .count());
    }

    @Test
    void detectsModuleProviderDeclaration() throws Exception {
        copyClass(ServiceContract.class);
        copyClass(ServiceProvider.class);
        copyClass(ExternalServiceProvider.class);
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_MODULE, "module-info", null, null, null);
        var module = writer.visitModule("example.services", 0, null);
        module.visitProvide(Type.getInternalName(ServiceContract.class),
                Type.getInternalName(ServiceProvider.class));
        module.visitProvide(Type.getInternalName(Runnable.class),
                Type.getInternalName(ExternalServiceProvider.class));
        module.visitEnd();
        writer.visitEnd();
        Files.write(tempDir.resolve("module-info.class"), writer.toByteArray());

        List<BytecodeDependencyScanner.StaticDependency> dependencies =
                BytecodeDependencyScanner.scan(List.of(tempDir));

        assertEquals(1, dependencies.stream()
                .filter(dependency -> dependency.fromClass()
                        .equals(ServiceProvider.class.getName()))
                .filter(dependency -> dependency.toClass()
                        .equals(ServiceContract.class.getName()))
                .filter(dependency -> dependency.kind().equals("SERVICE_PROVIDES"))
                .count());
        assertEquals(1, dependencies.stream()
                .filter(dependency -> dependency.fromClass()
                        .equals(ExternalServiceProvider.class.getName()))
                .filter(dependency -> dependency.toClass().equals(Runnable.class.getName()))
                .filter(dependency -> dependency.kind().equals("SERVICE_PROVIDES"))
                .count());
    }

    private void copyClass(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        Path target = tempDir.resolve(resource.substring(1));
        Files.createDirectories(target.getParent());
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertNotNull(input);
            Files.copy(input, target);
        }
    }

    static final class Consumer {
        private int value;

        Object create() {
            return new Constructed();
        }

        Object conditionalCreate(boolean enabled) {
            return enabled ? new BranchConstructed() : null;
        }

        void setValue(int value) {
            this.value = value;
        }

        int getValue() {
            return value;
        }
    }

    static final class Constructed {}

    static final class BranchConstructed {}

    interface ServiceContract {}

    static final class ServiceConsumer {
        ServiceLoader<ServiceContract> load() {
            ServiceLoader.load(Runnable.class);
            return ServiceLoader.load(ServiceContract.class);
        }
    }

    static final class ServiceProvider implements ServiceContract {}

    static final class ExternalServiceProvider implements Runnable {
        @Override
        public void run() {}
    }
}
