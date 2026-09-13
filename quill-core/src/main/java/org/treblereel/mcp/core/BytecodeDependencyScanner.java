package org.treblereel.mcp.core;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Extracts application-to-application references that only exist in bytecode instructions. */
public final class BytecodeDependencyScanner {

    private BytecodeDependencyScanner() {}

    public record StaticDependency(String fromClass, String toClass, String kind, int occurrences) {}

    private record Edge(String fromClass, String toClass, String kind) {}

    public static List<StaticDependency> scan(List<Path> classesDirectories) {
        return scan(ClassFileSnapshot.capture(classesDirectories));
    }

    public static List<StaticDependency> scan(ClassFileSnapshot classFiles) {
        List<ClassFileSnapshot.Entry> entries = classFiles.entries().stream()
                .sorted(Comparator.comparing(entry -> entry.path().toString()))
                .toList();
        Set<String> applicationClasses = new HashSet<>();
        for (ClassFileSnapshot.Entry entry : entries) {
            applicationClasses.add(className(new ClassReader(entry.bytecode()).getClassName()));
        }
        return scan(entries, applicationClasses);
    }

    public static List<StaticDependency> scan(
            ClassFileSnapshot classFiles, Collection<String> applicationClasses) {
        List<ClassFileSnapshot.Entry> entries = classFiles.entries().stream()
                .sorted(Comparator.comparing(entry -> entry.path().toString()))
                .toList();
        return scan(entries, Set.copyOf(applicationClasses));
    }

    private static List<StaticDependency> scan(
            List<ClassFileSnapshot.Entry> entries, Set<String> applicationClasses) {
        Map<Edge, Integer> edges = new LinkedHashMap<>();
        for (ClassFileSnapshot.Entry entry : entries) {
            new ClassReader(entry.bytecode()).accept(
                    new DependencyClassVisitor(applicationClasses, edges),
                    ClassReader.SKIP_FRAMES);
        }

        return edges.entrySet().stream()
                .map(entry -> new StaticDependency(entry.getKey().fromClass(),
                        entry.getKey().toClass(), entry.getKey().kind(), entry.getValue()))
                .sorted(Comparator.comparing(StaticDependency::fromClass)
                        .thenComparing(StaticDependency::toClass)
                        .thenComparing(StaticDependency::kind))
                .toList();
    }

    private static final class DependencyClassVisitor extends ClassVisitor {
        private final Set<String> applicationClasses;
        private final Map<Edge, Integer> edges;
        private String owner;

        private DependencyClassVisitor(Set<String> applicationClasses, Map<Edge, Integer> edges) {
            super(Opcodes.ASM9);
            this.applicationClasses = applicationClasses;
            this.edges = edges;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            owner = className(name);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeInsn(int opcode, String type) {
                    add(type, opcode == Opcodes.NEW ? "CONSTRUCTS" : "TYPE_USE");
                }

                @Override
                public void visitFieldInsn(int opcode, String fieldOwner, String fieldName,
                        String fieldDescriptor) {
                    add(fieldOwner, "FIELD_ACCESS");
                    addType(Type.getType(fieldDescriptor), "TYPE_USE");
                }

                @Override
                public void visitMethodInsn(int opcode, String methodOwner, String methodName,
                        String methodDescriptor, boolean isInterface) {
                    if (!"<init>".equals(methodName)) add(methodOwner, "CALLS");
                    addMethodTypes(methodDescriptor);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor,
                        Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                    addMethodTypes(descriptor);
                    add(bootstrapMethodHandle.getOwner(), "CALLS");
                    for (Object argument : bootstrapMethodArguments) {
                        if (argument instanceof Handle handle) {
                            add(handle.getOwner(), "CALLS");
                            addMethodTypes(handle.getDesc());
                        } else if (argument instanceof Type type) {
                            addType(type, "TYPE_USE");
                        }
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type type) addType(type, "TYPE_USE");
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                    addType(Type.getType(descriptor), "TYPE_USE");
                }

                @Override
                public void visitLocalVariable(String name, String descriptor, String signature,
                        org.objectweb.asm.Label start, org.objectweb.asm.Label end, int index) {
                    addType(Type.getType(descriptor), "TYPE_USE");
                }

                @Override
                public void visitTryCatchBlock(org.objectweb.asm.Label start,
                        org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) {
                    if (type != null) add(type, "TYPE_USE");
                }

                private void addMethodTypes(String methodDescriptor) {
                    if (methodDescriptor == null || !methodDescriptor.startsWith("(")) return;
                    for (Type type : Type.getArgumentTypes(methodDescriptor)) addType(type, "TYPE_USE");
                    addType(Type.getReturnType(methodDescriptor), "TYPE_USE");
                }

                private void addType(Type type, String kind) {
                    if (type == null) return;
                    if (type.getSort() == Type.ARRAY) {
                        addType(type.getElementType(), kind);
                    } else if (type.getSort() == Type.OBJECT) {
                        add(type.getInternalName(), kind);
                    } else if (type.getSort() == Type.METHOD) {
                        addMethodTypes(type.getDescriptor());
                    }
                }

                private void add(String internalName, String kind) {
                    if (internalName == null) return;
                    String target = className(internalName);
                    if (owner.equals(target) || !applicationClasses.contains(target)) return;
                    edges.merge(new Edge(owner, target, kind), 1, Integer::sum);
                }
            };
        }
    }

    private static String className(String internalName) {
        return internalName.replace('/', '.');
    }
}
