package org.treblereel.mcp.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.treblereel.mcp.model.ClassRecord;

/** Finds configuration keys passed to common programmatic configuration APIs. */
final class ProgrammaticConfigurationScanner {

    static final String DYNAMIC_KEY = "<dynamic>";

    private ProgrammaticConfigurationScanner() {}

    record ScanResult(
            List<ConfigurationScanner.Usage> configurationUsages,
            List<ConfigurationScanner.ResourceUsage> resourceUsages) {
        static final ScanResult EMPTY = new ScanResult(List.of(), List.of());
    }

    static ScanResult scan(
            ClassFileSnapshot classFiles, Map<String, Integer> classNameToId,
            Map<String, ClassRecord> classesByName) {
        List<ConfigurationScanner.Usage> result = new ArrayList<>();
        List<ConfigurationScanner.ResourceUsage> resources = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (ClassFileSnapshot.Entry entry : classFiles.entries().stream()
                .sorted(Comparator.comparing(value -> value.path().toString())).toList()) {
            new ClassReader(entry.bytecode()).accept(new ClassVisitor(Opcodes.ASM9) {
                private String className;

                @Override
                public void visit(int version, int access, String name, String signature,
                        String superName, String[] interfaces) {
                    className = name.replace('/', '.');
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    Integer classId = classNameToId.get(className);
                    if (classId == null) return null;
                    return new LookupVisitor(classId, className, name,
                            classesByName.get(className), result, resources, unique);
                }
            }, ClassReader.SKIP_FRAMES);
        }
        List<ConfigurationScanner.Usage> configurations = result.stream()
                .sorted(Comparator.comparing(ConfigurationScanner.Usage::key)
                .thenComparing(ConfigurationScanner.Usage::className)
                .thenComparing(value -> value.member() == null ? "" : value.member()))
                .toList();
        List<ConfigurationScanner.ResourceUsage> resourceUsages = resources.stream()
                .sorted(Comparator.comparing(ConfigurationScanner.ResourceUsage::resourcePath)
                        .thenComparing(ConfigurationScanner.ResourceUsage::className)
                        .thenComparing(ConfigurationScanner.ResourceUsage::member))
                .toList();
        return new ScanResult(configurations, resourceUsages);
    }

    private static final class LookupVisitor extends MethodVisitor {
        private static final Object UNKNOWN = new Object();
        private record ClassLiteral(String className) {}
        private record NormalizedResource(String path, String kind) {}
        private final int classId;
        private final String className;
        private final String method;
        private final ClassRecord cls;
        private final List<ConfigurationScanner.Usage> target;
        private final List<ConfigurationScanner.ResourceUsage> resources;
        private final Set<String> unique;
        private final Deque<Object> stack = new ArrayDeque<>();

        private LookupVisitor(int classId, String className, String method, ClassRecord cls,
                List<ConfigurationScanner.Usage> target,
                List<ConfigurationScanner.ResourceUsage> resources, Set<String> unique) {
            super(Opcodes.ASM9);
            this.classId = classId;
            this.className = className;
            this.method = method;
            this.cls = cls;
            this.target = target;
            this.resources = resources;
            this.unique = unique;
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof String) stack.push(value);
            else if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                stack.push(new ClassLiteral(type.getClassName()));
            } else stack.push(UNKNOWN);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) stack.push(UNKNOWN);
            else if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) pop();
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.GETSTATIC) stack.push(UNKNOWN);
            else if (opcode == Opcodes.GETFIELD) {
                pop();
                stack.push(UNKNOWN);
            } else if (opcode == Opcodes.PUTSTATIC) pop();
            else {
                pop();
                pop();
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW) stack.push(UNKNOWN);
            else if (opcode == Opcodes.ANEWARRAY) {
                pop();
                stack.push(UNKNOWN);
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                boolean isInterface) {
            Type[] argumentTypes = Type.getArgumentTypes(descriptor);
            Object[] arguments = new Object[argumentTypes.length];
            for (int i = argumentTypes.length - 1; i >= 0; i--) arguments[i] = pop();
            Object receiver = opcode == Opcodes.INVOKESTATIC ? null : pop();

            String api = supportedApi(owner, name, descriptor);
            if (api != null && arguments.length > 0) {
                String key = arguments[0] instanceof String value ? value : DYNAMIC_KEY;
                String kind = key.equals(DYNAMIC_KEY) ? "dynamic_config_key" : "config_key";
                String identity = classId + "\n" + method + "\n" + api + "\n" + key;
                if (unique.add(identity)) {
                    target.add(new ConfigurationScanner.Usage(key, kind, classId, className,
                            method, null, api, cls == null ? null : cls.sourceFile(),
                            cls == null ? null : cls.module(),
                            cls == null ? null : cls.sourceSet()));
                }
            }
            String resourceApi = supportedResourceApi(owner, name, descriptor);
            if (resourceApi != null && arguments.length > 0) {
                NormalizedResource normalized = arguments[0] instanceof String value
                        ? normalizeResource(value, resourceApi, className, receiver)
                        : new NormalizedResource(DYNAMIC_KEY, "dynamic_resource");
                String identity = classId + "\n" + method + "\n" + resourceApi + "\n"
                        + normalized.path();
                if (unique.add(identity)) {
                    resources.add(new ConfigurationScanner.ResourceUsage(normalized.path(),
                            normalized.kind(),
                            classId, className, method, resourceApi,
                            cls == null ? null : cls.sourceFile(),
                            cls == null ? null : cls.module(),
                            cls == null ? null : cls.sourceSet()));
                }
            }
            if (Type.getReturnType(descriptor).getSort() != Type.VOID) stack.push(UNKNOWN);
        }

        @Override
        public void visitInsn(int opcode) {
            switch (opcode) {
                case Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0,
                        Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3,
                        Opcodes.ICONST_4, Opcodes.ICONST_5 -> stack.push(UNKNOWN);
                case Opcodes.DUP -> stack.push(stack.isEmpty() ? UNKNOWN : stack.peek());
                case Opcodes.POP, Opcodes.ARETURN, Opcodes.IRETURN, Opcodes.FRETURN -> pop();
                case Opcodes.POP2, Opcodes.LRETURN, Opcodes.DRETURN -> { pop(); pop(); }
                case Opcodes.RETURN, Opcodes.NOP -> { }
                default -> stack.clear();
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) stack.push(UNKNOWN);
            else stack.clear();
        }

        @Override
        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
            stack.clear();
        }

        private Object pop() {
            return stack.isEmpty() ? UNKNOWN : stack.pop();
        }
    }

    private static String supportedApi(String owner, String name, String descriptor) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        if (arguments.length == 0 || !arguments[0].equals(Type.getType(String.class))) return null;
        if (owner.equals("java/lang/System") && name.equals("getProperty")) {
            return "java.lang.System#getProperty";
        }
        if (owner.equals("org/springframework/core/env/Environment")
                && (name.equals("getProperty") || name.equals("getRequiredProperty"))) {
            return "org.springframework.core.env.Environment#" + name;
        }
        if (owner.equals("org/eclipse/microprofile/config/Config")
                && (name.equals("getValue") || name.equals("getOptionalValue"))) {
            return "org.eclipse.microprofile.config.Config#" + name;
        }
        return null;
    }

    private static String supportedResourceApi(String owner, String name, String descriptor) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        if (arguments.length == 0 || !arguments[0].equals(Type.getType(String.class))) return null;
        if ((owner.equals("java/lang/Class") || owner.equals("java/lang/ClassLoader"))
                && (name.equals("getResource") || name.equals("getResourceAsStream"))) {
            return owner.replace('/', '.') + "#" + name;
        }
        if (owner.equals("org/springframework/core/io/ResourceLoader")
                && name.equals("getResource")) {
            return "org.springframework.core.io.ResourceLoader#getResource";
        }
        if (owner.equals("java/util/ResourceBundle") && name.equals("getBundle")) {
            return "java.util.ResourceBundle#getBundle";
        }
        return null;
    }

    private static LookupVisitor.NormalizedResource normalizeResource(
            String value, String api, String consumerClass, Object receiver) {
        String normalized = value.replace('\\', '/');
        if (api.equals("java.util.ResourceBundle#getBundle")) {
            normalized = normalized.replace('.', '/') + ".properties";
            return normalized.isBlank()
                    ? new LookupVisitor.NormalizedResource(DYNAMIC_KEY, "dynamic_resource")
                    : new LookupVisitor.NormalizedResource(normalized, "resource_bundle");
        }
        if (api.equals("org.springframework.core.io.ResourceLoader#getResource")) {
            if (normalized.startsWith("classpath*:")) normalized = normalized.substring(11);
            else if (normalized.startsWith("classpath:")) normalized = normalized.substring(10);
            else if (normalized.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
                return new LookupVisitor.NormalizedResource(normalized, "external_resource");
            }
        }
        boolean absolute = normalized.startsWith("/");
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isBlank()) {
            return new LookupVisitor.NormalizedResource(DYNAMIC_KEY, "dynamic_resource");
        }
        if (api.startsWith("java.lang.Class#") && !absolute) {
            String anchor = receiver instanceof LookupVisitor.ClassLiteral literal
                    ? literal.className() : consumerClass;
            int packageEnd = anchor.lastIndexOf('.');
            if (packageEnd >= 0) normalized = anchor.substring(0, packageEnd)
                    .replace('.', '/') + "/" + normalized;
        }
        return new LookupVisitor.NormalizedResource(normalized, "resource");
    }
}
