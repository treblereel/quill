package org.treblereel.mcp.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Extracts application-to-application references that only exist in bytecode instructions. */
public final class BytecodeDependencyScanner {

    private BytecodeDependencyScanner() {}

    public record StaticDependency(
            String fromClass, String toClass, String kind, int occurrences,
            List<Integer> evidenceLines) {}

    public record StaticMethodCall(
            String fromClass,
            String fromMethod,
            String fromDescriptor,
            String toClass,
            String toMethod,
            String toDescriptor,
            String invocationKind,
            int occurrences,
            List<Integer> evidenceLines,
            List<Integer> instructionOrdinals,
            int callerBranchCount,
            int callerExceptionHandlerCount,
            List<String> callerControlFlowEdges) {}

    public record StaticFieldAccess(
            String fromClass,
            String fromMethod,
            String fromDescriptor,
            String toClass,
            String fieldName,
            String fieldDescriptor,
            String accessKind,
            int occurrences,
            List<Integer> evidenceLines,
            List<Integer> instructionOrdinals) {}

    public record ScanResult(
            List<StaticDependency> dependencies,
            List<StaticMethodCall> methodCalls,
            List<StaticFieldAccess> fieldAccesses) {}

    private record Edge(String fromClass, String toClass, String kind) {}

    private record CallEdge(
            String fromClass, String fromMethod, String fromDescriptor,
            String toClass, String toMethod, String toDescriptor, String invocationKind) {}

    private record FieldEdge(
            String fromClass, String fromMethod, String fromDescriptor,
            String toClass, String fieldName, String fieldDescriptor, String accessKind) {}

    public static List<StaticDependency> scan(List<Path> classesDirectories) {
        return analyze(ClassFileSnapshot.capture(classesDirectories)).dependencies();
    }

    public static List<StaticDependency> scan(ClassFileSnapshot classFiles) {
        return analyze(classFiles).dependencies();
    }

    public static ScanResult analyze(ClassFileSnapshot classFiles) {
        List<ClassFileSnapshot.Entry> entries = classFiles.entries().stream()
                .sorted(Comparator.comparing(entry -> entry.path().toString()))
                .toList();
        Set<String> applicationClasses = new HashSet<>();
        for (ClassFileSnapshot.Entry entry : entries) {
            applicationClasses.add(className(new ClassReader(entry.bytecode()).getClassName()));
        }
        return analyze(entries, applicationClasses);
    }

    public static List<StaticDependency> scan(
            ClassFileSnapshot classFiles, Collection<String> applicationClasses) {
        List<ClassFileSnapshot.Entry> entries = classFiles.entries().stream()
                .sorted(Comparator.comparing(entry -> entry.path().toString()))
                .toList();
        return analyze(entries, Set.copyOf(applicationClasses)).dependencies();
    }

    public static ScanResult analyze(
            ClassFileSnapshot classFiles, Collection<String> applicationClasses) {
        List<ClassFileSnapshot.Entry> entries = classFiles.entries().stream()
                .sorted(Comparator.comparing(entry -> entry.path().toString()))
                .toList();
        return analyze(entries, Set.copyOf(applicationClasses));
    }

    private static ScanResult analyze(
            List<ClassFileSnapshot.Entry> entries, Set<String> applicationClasses) {
        Map<Edge, EdgeEvidence> edges = new LinkedHashMap<>();
        Map<CallEdge, EdgeEvidence> calls = new LinkedHashMap<>();
        Map<FieldEdge, EdgeEvidence> fields = new LinkedHashMap<>();
        Map<MethodKey, ControlFlowEvidence> controlFlow = new LinkedHashMap<>();
        for (ClassFileSnapshot.Entry entry : entries) {
            new ClassReader(entry.bytecode()).accept(
                    new DependencyClassVisitor(applicationClasses, edges, calls, fields,
                            controlFlow),
                    ClassReader.SKIP_FRAMES);
        }

        List<StaticDependency> dependencies = edges.entrySet().stream()
                .map(entry -> new StaticDependency(entry.getKey().fromClass(),
                        entry.getKey().toClass(), entry.getKey().kind(), entry.getValue().occurrences,
                        List.copyOf(entry.getValue().lines)))
                .sorted(Comparator.comparing(StaticDependency::fromClass)
                        .thenComparing(StaticDependency::toClass)
                        .thenComparing(StaticDependency::kind))
                .toList();
        List<StaticMethodCall> methodCalls = calls.entrySet().stream()
                .map(entry -> new StaticMethodCall(
                        entry.getKey().fromClass(), entry.getKey().fromMethod(),
                        entry.getKey().fromDescriptor(), entry.getKey().toClass(),
                        entry.getKey().toMethod(), entry.getKey().toDescriptor(),
                        entry.getKey().invocationKind(), entry.getValue().occurrences,
                        List.copyOf(entry.getValue().lines),
                        List.copyOf(entry.getValue().instructionOrdinals),
                        controlFlow.getOrDefault(new MethodKey(entry.getKey().fromClass(),
                                entry.getKey().fromMethod(), entry.getKey().fromDescriptor()),
                                new ControlFlowEvidence()).branchCount,
                        controlFlow.getOrDefault(new MethodKey(entry.getKey().fromClass(),
                                entry.getKey().fromMethod(), entry.getKey().fromDescriptor()),
                                new ControlFlowEvidence()).exceptionHandlerCount,
                        List.copyOf(controlFlow.getOrDefault(new MethodKey(
                                entry.getKey().fromClass(), entry.getKey().fromMethod(),
                                entry.getKey().fromDescriptor()), new ControlFlowEvidence()).edges)))
                .sorted(Comparator.comparing(StaticMethodCall::fromClass)
                        .thenComparing(StaticMethodCall::fromMethod)
                        .thenComparing(StaticMethodCall::fromDescriptor)
                        .thenComparing(StaticMethodCall::toClass)
                        .thenComparing(StaticMethodCall::toMethod)
                        .thenComparing(StaticMethodCall::toDescriptor))
                .toList();
        List<StaticFieldAccess> fieldAccesses = fields.entrySet().stream()
                .map(entry -> new StaticFieldAccess(
                        entry.getKey().fromClass(), entry.getKey().fromMethod(),
                        entry.getKey().fromDescriptor(), entry.getKey().toClass(),
                        entry.getKey().fieldName(), entry.getKey().fieldDescriptor(),
                        entry.getKey().accessKind(), entry.getValue().occurrences,
                        List.copyOf(entry.getValue().lines),
                        List.copyOf(entry.getValue().instructionOrdinals)))
                .sorted(Comparator.comparing(StaticFieldAccess::fromClass)
                        .thenComparing(StaticFieldAccess::fromMethod)
                        .thenComparing(StaticFieldAccess::fromDescriptor)
                        .thenComparing(StaticFieldAccess::toClass)
                        .thenComparing(StaticFieldAccess::fieldName)
                        .thenComparing(StaticFieldAccess::fieldDescriptor)
                        .thenComparing(StaticFieldAccess::accessKind))
                .toList();
        return new ScanResult(dependencies, methodCalls, fieldAccesses);
    }

    private static final class DependencyClassVisitor extends ClassVisitor {
        private final Set<String> applicationClasses;
        private final Map<Edge, EdgeEvidence> edges;
        private final Map<CallEdge, EdgeEvidence> calls;
        private final Map<FieldEdge, EdgeEvidence> fields;
        private final Map<MethodKey, ControlFlowEvidence> controlFlow;
        private String owner;

        private DependencyClassVisitor(
                Set<String> applicationClasses, Map<Edge, EdgeEvidence> edges,
                Map<CallEdge, EdgeEvidence> calls, Map<FieldEdge, EdgeEvidence> fields,
                Map<MethodKey, ControlFlowEvidence> controlFlow) {
            super(Opcodes.ASM9);
            this.applicationClasses = applicationClasses;
            this.edges = edges;
            this.calls = calls;
            this.fields = fields;
            this.controlFlow = controlFlow;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            owner = className(name);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            String callerMethod = name;
            String callerDescriptor = descriptor;
            ControlFlowEvidence methodControlFlow = controlFlow.computeIfAbsent(
                    new MethodKey(owner, name, descriptor), ignored -> new ControlFlowEvidence());
            return new MethodVisitor(Opcodes.ASM9) {
                private Type directClassLiteral;
                private int currentLine;
                private int instructionOrdinal;
                private final Map<org.objectweb.asm.Label, Integer> labelOrdinals =
                        new IdentityHashMap<>();
                private final List<PendingEdge> pendingEdges = new ArrayList<>();
                private final List<PendingExceptionRegion> exceptionRegions = new ArrayList<>();
                private final Set<Integer> noFallthrough = new HashSet<>();

                @Override
                public void visitLineNumber(int line, org.objectweb.asm.Label start) {
                    currentLine = line;
                }

                @Override
                public void visitLabel(org.objectweb.asm.Label label) {
                    labelOrdinals.put(label, instructionOrdinal + 1);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    nextInstruction();
                    directClassLiteral = null;
                    add(type, opcode == Opcodes.NEW ? "CONSTRUCTS" : "TYPE_USE");
                }

                @Override
                public void visitFieldInsn(int opcode, String fieldOwner, String fieldName,
                        String fieldDescriptor) {
                    nextInstruction();
                    directClassLiteral = null;
                    addFieldAccess(fieldOwner, fieldName, fieldDescriptor,
                            fieldAccessKind(opcode));
                    add(fieldOwner, "FIELD_ACCESS");
                    addType(Type.getType(fieldDescriptor), "TYPE_USE");
                }

                @Override
                public void visitMethodInsn(int opcode, String methodOwner, String methodName,
                        String methodDescriptor, boolean isInterface) {
                    nextInstruction();
                    if (methodOwner.equals("java/util/ServiceLoader")
                            && (methodName.equals("load") || methodName.equals("loadInstalled"))
                            && directClassLiteral != null) {
                        addService(owner, directClassLiteral.getInternalName(),
                                "SERVICE_CONSUMES");
                    }
                    addCall(methodOwner, methodName, methodDescriptor,
                            invocationKind(opcode));
                    directClassLiteral = null;
                    if (!"<init>".equals(methodName)) add(methodOwner, "CALLS");
                    addMethodTypes(methodDescriptor);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor,
                        Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                    nextInstruction();
                    directClassLiteral = null;
                    addMethodTypes(descriptor);
                    add(bootstrapMethodHandle.getOwner(), "CALLS");
                    for (Object argument : bootstrapMethodArguments) {
                        if (argument instanceof Handle handle) {
                            String accessKind = fieldHandleAccessKind(handle.getTag());
                            if (accessKind == null) {
                                addCall(handle.getOwner(), handle.getName(), handle.getDesc(),
                                        "dynamic");
                                add(handle.getOwner(), "CALLS");
                                addMethodTypes(handle.getDesc());
                            } else {
                                addFieldAccess(handle.getOwner(), handle.getName(),
                                        handle.getDesc(), accessKind);
                                add(handle.getOwner(), "FIELD_ACCESS");
                                addType(Type.getType(handle.getDesc()), "TYPE_USE");
                            }
                        } else if (argument instanceof Type type) {
                            addType(type, "TYPE_USE");
                        }
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    nextInstruction();
                    directClassLiteral = value instanceof Type type
                            && type.getSort() == Type.OBJECT ? type : null;
                    if (value instanceof Type type) addType(type, "TYPE_USE");
                }

                @Override
                public void visitInsn(int opcode) {
                    nextInstruction();
                    if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)
                            || opcode == Opcodes.ATHROW) {
                        noFallthrough.add(instructionOrdinal);
                    }
                    directClassLiteral = null;
                }

                @Override
                public void visitIntInsn(int opcode, int operand) {
                    nextInstruction();
                    directClassLiteral = null;
                }

                @Override
                public void visitVarInsn(int opcode, int variable) {
                    nextInstruction();
                    directClassLiteral = null;
                }

                @Override
                public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                    nextInstruction();
                    methodControlFlow.branchCount++;
                    pendingEdges.add(new PendingEdge(instructionOrdinal, label));
                    if (opcode == Opcodes.GOTO || opcode == Opcodes.JSR) {
                        noFallthrough.add(instructionOrdinal);
                    }
                    directClassLiteral = null;
                }

                @Override
                public void visitIincInsn(int variable, int increment) {
                    nextInstruction();
                    directClassLiteral = null;
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                    nextInstruction();
                    directClassLiteral = null;
                    addType(Type.getType(descriptor), "TYPE_USE");
                }

                @Override
                public void visitTableSwitchInsn(int min, int max,
                        org.objectweb.asm.Label dflt, org.objectweb.asm.Label... labels) {
                    nextInstruction();
                    methodControlFlow.branchCount++;
                    noFallthrough.add(instructionOrdinal);
                    pendingEdges.add(new PendingEdge(instructionOrdinal, dflt));
                    for (org.objectweb.asm.Label label : labels) {
                        pendingEdges.add(new PendingEdge(instructionOrdinal, label));
                    }
                    directClassLiteral = null;
                }

                @Override
                public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys,
                        org.objectweb.asm.Label[] labels) {
                    nextInstruction();
                    methodControlFlow.branchCount++;
                    noFallthrough.add(instructionOrdinal);
                    pendingEdges.add(new PendingEdge(instructionOrdinal, dflt));
                    for (org.objectweb.asm.Label label : labels) {
                        pendingEdges.add(new PendingEdge(instructionOrdinal, label));
                    }
                    directClassLiteral = null;
                }

                @Override
                public void visitEnd() {
                    for (int ordinal = 1; ordinal < instructionOrdinal; ordinal++) {
                        if (!noFallthrough.contains(ordinal)) {
                            methodControlFlow.edges.add(ordinal + ">" + (ordinal + 1));
                        }
                    }
                    for (PendingEdge edge : pendingEdges) {
                        Integer target = labelOrdinals.get(edge.target());
                        if (target != null && target >= 1 && target <= instructionOrdinal) {
                            methodControlFlow.edges.add(edge.source() + ">" + target);
                        }
                    }
                    for (PendingExceptionRegion region : exceptionRegions) {
                        Integer start = labelOrdinals.get(region.start());
                        Integer end = labelOrdinals.get(region.end());
                        Integer handler = labelOrdinals.get(region.handler());
                        if (start == null || end == null || handler == null) continue;
                        for (int ordinal = start; ordinal < end && ordinal <= instructionOrdinal;
                                ordinal++) {
                            methodControlFlow.edges.add(ordinal + ">" + handler);
                        }
                    }
                }

                @Override
                public void visitLocalVariable(String name, String descriptor, String signature,
                        org.objectweb.asm.Label start, org.objectweb.asm.Label end, int index) {
                    addType(Type.getType(descriptor), "TYPE_USE");
                }

                @Override
                public void visitTryCatchBlock(org.objectweb.asm.Label start,
                        org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) {
                    methodControlFlow.exceptionHandlerCount++;
                    exceptionRegions.add(new PendingExceptionRegion(start, end, handler));
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
                    edges.computeIfAbsent(new Edge(owner, target, kind), ignored -> new EdgeEvidence())
                            .add(currentLine);
                }

                private void nextInstruction() {
                    instructionOrdinal++;
                }

                private void addCall(String internalName, String methodName,
                        String methodDescriptor, String kind) {
                    if (internalName == null || methodName == null || methodDescriptor == null
                            || !methodDescriptor.startsWith("(")) return;
                    String target = className(internalName);
                    if (!applicationClasses.contains(target)) return;
                    calls.computeIfAbsent(new CallEdge(owner, callerMethod, callerDescriptor,
                            target, methodName, methodDescriptor, kind),
                            ignored -> new EdgeEvidence()).add(currentLine, instructionOrdinal);
                }

                private void addFieldAccess(String internalName, String fieldName,
                        String fieldDescriptor, String kind) {
                    if (internalName == null || fieldName == null || fieldDescriptor == null
                            || kind == null) return;
                    String target = className(internalName);
                    if (!applicationClasses.contains(target)) return;
                    fields.computeIfAbsent(new FieldEdge(owner, callerMethod, callerDescriptor,
                            target, fieldName, fieldDescriptor, kind),
                            ignored -> new EdgeEvidence()).add(currentLine, instructionOrdinal);
                }
            };
        }

        @Override
        public ModuleVisitor visitModule(String name, int access, String version) {
            return new ModuleVisitor(Opcodes.ASM9) {
                @Override
                public void visitProvide(String service, String... providers) {
                    for (String provider : providers) {
                        addService(provider, service, "SERVICE_PROVIDES");
                    }
                }
            };
        }

        private void addService(
                String fromInternalName, String toInternalName, String kind) {
            String from = className(fromInternalName);
            String to = className(toInternalName);
            if (from.equals(to) || !applicationClasses.contains(from)) {
                return;
            }
            edges.computeIfAbsent(new Edge(from, to, kind), ignored -> new EdgeEvidence())
                    .add(0);
        }
    }

    private static final class EdgeEvidence {
        private int occurrences;
        private final Set<Integer> lines = new TreeSet<>();
        private final List<Integer> instructionOrdinals = new ArrayList<>();

        private void add(int line) {
            occurrences++;
            if (line > 0) lines.add(line);
        }

        private void add(int line, int instructionOrdinal) {
            add(line);
            if (instructionOrdinal > 0) instructionOrdinals.add(instructionOrdinal);
        }
    }

    private record MethodKey(String owner, String method, String descriptor) {}

    private record PendingEdge(int source, org.objectweb.asm.Label target) {}

    private record PendingExceptionRegion(org.objectweb.asm.Label start,
            org.objectweb.asm.Label end, org.objectweb.asm.Label handler) {}

    private static final class ControlFlowEvidence {
        private int branchCount;
        private int exceptionHandlerCount;
        private final Set<String> edges = new TreeSet<>();
    }

    private static String className(String internalName) {
        return internalName.replace('/', '.');
    }

    private static String invocationKind(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKESTATIC -> "static";
            case Opcodes.INVOKEINTERFACE -> "interface";
            case Opcodes.INVOKESPECIAL -> "special";
            case Opcodes.INVOKEVIRTUAL -> "virtual";
            default -> "unknown";
        };
    }

    private static String fieldAccessKind(int opcode) {
        return switch (opcode) {
            case Opcodes.GETFIELD -> "read_instance";
            case Opcodes.PUTFIELD -> "write_instance";
            case Opcodes.GETSTATIC -> "read_static";
            case Opcodes.PUTSTATIC -> "write_static";
            default -> "unknown";
        };
    }

    private static String fieldHandleAccessKind(int tag) {
        return switch (tag) {
            case Opcodes.H_GETFIELD -> "read_instance";
            case Opcodes.H_PUTFIELD -> "write_instance";
            case Opcodes.H_GETSTATIC -> "read_static";
            case Opcodes.H_PUTSTATIC -> "write_static";
            default -> null;
        };
    }
}
