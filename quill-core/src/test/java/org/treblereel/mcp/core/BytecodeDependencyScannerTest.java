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
import java.util.concurrent.Executor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BytecodeDependencyScannerTest {

    @TempDir Path tempDir;
    private static final String LOOKUP = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;";
    private static final Handle META = new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","metafactory",
            LOOKUP+"Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",false);
    private static final Handle ALT = new Handle(Opcodes.H_INVOKESTATIC,"java/lang/invoke/LambdaMetafactory","altMetafactory",
            LOOKUP+"[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",false);
    private BytecodeDependencyScanner.ScanResult dynamic(Handle bootstrap,Object... arguments) throws Exception {
        ClassWriter target=new ClassWriter(0);
        target.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"p/Target",null,"java/lang/Object",null); target.visitEnd();
        Files.write(tempDir.resolve("Target.class"),target.toByteArray());
        ClassWriter caller=new ClassWriter(0);
        caller.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,"p/Caller",null,"java/lang/Object",null);
        var method=caller.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"create","()V",null,null);
        method.visitCode(); method.visitInvokeDynamicInsn("run","()Ljava/lang/Runnable;",bootstrap,arguments);
        method.visitInsn(Opcodes.POP); method.visitInsn(Opcodes.RETURN); method.visitMaxs(1,0); method.visitEnd(); caller.visitEnd();
        Files.write(tempDir.resolve("Caller.class"),caller.toByteArray());
        return BytecodeDependencyScanner.analyze(ClassFileSnapshot.capture(List.of(tempDir)));
    }
    @Test void standardLambdaBootstrapHasOneCallbackReferenceAndNoExecutionClaim() throws Exception {
        var handle=new Handle(Opcodes.H_INVOKESTATIC,"p/Target","run","()V",false);
        for (var bootstrap:List.of(META,ALT)) {
            var args=new java.util.ArrayList<Object>(List.of(Type.getMethodType("()V"),handle,Type.getMethodType("()V")));
            if (bootstrap==ALT) args.add(1); // serializable callback
            var result=dynamic(bootstrap,args.toArray());
            assertEquals(1,result.methodCalls().size());
            assertEquals("dynamic",result.methodCalls().get(0).invocationKind());
            assertEquals("run",result.methodCalls().get(0).toMethod());
            assertEquals(1,result.dependencies().stream().filter(d -> d.kind().equals("CALLBACK_REFERENCE")).count());
        }
    }
    @Test void unknownBootstrapMethodAndFieldHandlesRemainReferencesWithoutCallsOrAccesses() throws Exception {
        var bootstrap=new Handle(Opcodes.H_INVOKESTATIC,"p/Bootstrap","create",ALT.getDesc(),false);
        var result=dynamic(bootstrap,new Handle(Opcodes.H_INVOKESTATIC,"p/Target","unused","()V",false),
                new Handle(Opcodes.H_GETFIELD,"p/Target","unused","I",false));
        assertEquals(0,result.methodCalls().size()); assertEquals(0,result.fieldAccesses().size());
        assertEquals(java.util.Set.of("METHOD_HANDLE_REFERENCE","FIELD_HANDLE_REFERENCE"),result.dependencies().stream()
                .filter(d -> d.toClass().equals("p.Target") && d.kind().endsWith("HANDLE_REFERENCE"))
                .map(BytecodeDependencyScanner.StaticDependency::kind).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void malformedLambdaSlotsFlagsCountsAndBootstrapDescriptorsDoNotBecomeCalls() throws Exception {
        var handle=new Handle(Opcodes.H_INVOKESTATIC,"p/Target","run","()V",false);
        Object sam=Type.getMethodType("()V");
        for (Object[] args:List.of(new Object[]{sam,handle,sam},new Object[]{sam,handle,sam,8},
                new Object[]{sam,handle,sam,2,-1},new Object[]{sam,handle,sam,2,1},
                new Object[]{sam,handle,sam,4,1,Type.getType("Ljava/lang/String;")},
                new Object[]{handle,sam,sam,0},new Object[]{sam,handle,sam,0,handle})) {
            assertEquals(0,dynamic(ALT,args).methodCalls().size());
        }
        assertEquals(0,dynamic(new Handle(Opcodes.H_INVOKESTATIC,META.getOwner(),META.getName(),ALT.getDesc(),false),sam,handle,sam).methodCalls().size());
        assertEquals(0,dynamic(META,sam,handle,sam,handle).methodCalls().size());
        assertEquals(0,dynamic(META,sam,new Handle(Opcodes.H_GETSTATIC,"p/Target","x","I",false),sam).fieldAccesses().size());
    }
    @Test void alternateMarkerAndBridgeSlotsAreAcceptedOnlyWhenStructurallyValid() throws Exception {
        var handle=new Handle(Opcodes.H_INVOKESTATIC,"p/Target","run","()V",false);
        var result=dynamic(ALT,Type.getMethodType("()V"),handle,Type.getMethodType("()V"),6,
                1,Type.getType("Ljava/lang/Runnable;"),1,Type.getMethodType("()V"));
        assertEquals(1,result.methodCalls().size());
    }
    @Test void selfOwnedUnknownHandleReferencesAreRetainedWithoutCreatingCallEdges() throws Exception {
        var bootstrap=new Handle(Opcodes.H_INVOKESTATIC,"p/Bootstrap","create",ALT.getDesc(),false);
        var result=dynamic(bootstrap,new Handle(Opcodes.H_INVOKESTATIC,"p/Caller","unused","()V",false));
        assertEquals(0,result.methodCalls().size());
        assertEquals(1,result.dependencies().stream().filter(d -> d.fromClass().equals("p.Caller") && d.toClass().equals("p.Caller")
                && d.kind().equals("METHOD_HANDLE_REFERENCE")).count());
    }

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
                .filter(call -> !call.callerControlFlowEdges().isEmpty())
                .count());
        assertEquals(1, result.methodCalls().stream()
                .filter(call -> call.fromClass().equals(Consumer.class.getName()))
                .filter(call -> call.fromMethod().equals("guardedCreate"))
                .filter(call -> call.toClass().equals(GuardedConstructed.class.getName()))
                .filter(call -> call.callerExceptionHandlerCount() == 1)
                .filter(call -> call.callerControlFlowEdges().stream()
                        .anyMatch(edge -> edge.endsWith(">5")))
                .count());
        assertEquals(1, result.methodCalls().stream()
                .filter(call -> call.fromClass().equals(Consumer.class.getName()))
                .filter(call -> call.fromMethod().equals("asyncCreate"))
                .filter(call -> call.toClass().equals(AsyncConstructed.class.getName()))
                .filter(call -> call.callerAsyncBoundaries().stream()
                        .anyMatch(boundary -> boundary.endsWith("Executor.execute")))
                .filter(call -> call.callerExternalCalls().stream()
                        .anyMatch(event -> event.contains("java.util.concurrent.Executor|execute")))
                .count());
        assertEquals(1, result.methodCalls().stream()
                .filter(call -> call.fromClass().equals(Consumer.class.getName()))
                .filter(call -> call.fromMethod().equals("nestedFinallyCreate"))
                .filter(call -> call.toClass().equals(NestedConstructed.class.getName()))
                .filter(call -> call.callerExceptionHandlerCount() >= 2)
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

        Object guardedCreate() {
            try {
                return new GuardedConstructed();
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        Object asyncCreate(Executor executor) {
            executor.execute(() -> {});
            return new AsyncConstructed();
        }

        Object nestedFinallyCreate() {
            try {
                try {
                    return new NestedConstructed();
                } finally {
                    value++;
                }
            } finally {
                value++;
            }
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

    static final class GuardedConstructed {}

    static final class AsyncConstructed {}

    static final class NestedConstructed {}

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
