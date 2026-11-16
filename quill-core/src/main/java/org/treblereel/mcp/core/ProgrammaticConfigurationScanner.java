package org.treblereel.mcp.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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
import org.treblereel.mcp.model.ResourceUsageRecord;

/** Finds configuration keys passed to common programmatic configuration APIs. */
final class ProgrammaticConfigurationScanner {

    static final String DYNAMIC_KEY = "<dynamic>";

    private ProgrammaticConfigurationScanner() {}

    record ScanResult(
            List<ConfigurationScanner.Usage> configurationUsages,
            List<ResourceUsageRecord> resourceUsages) {
        static final ScanResult EMPTY = new ScanResult(List.of(), List.of());
    }

    /**
     * An application method passing its String parameter {@code param} as the key of a supported
     * API, directly or through another wrapper. Apollo AQ-039: PortalConfig reads
     * emergencyPublish.supported.envs through RefreshableConfig#getArrayProperty -> getValue ->
     * Environment#getProperty, and only direct literal calls were indexed.
     */
    record Wrapper(String className, String method, int param, String api) {}

    private static final int WRAPPER_PASSES = 4;

    static ScanResult scan(
            ClassFileSnapshot classFiles,
            Map<String, Integer> classNameToId,
            Map<String, ClassRecord> classesByName) {
        Map<String, Wrapper> wrappers = new HashMap<>();
        ScanResult scanned = ScanResult.EMPTY;
        for (int pass = 0; pass < WRAPPER_PASSES; pass++) {
            int known = wrappers.size();
            scanned = scanOnce(classFiles, classNameToId, classesByName, wrappers);
            if (wrappers.size() == known) break;
        }
        return scanned;
    }

    private static ScanResult scanOnce(
            ClassFileSnapshot classFiles,
            Map<String, Integer> classNameToId,
            Map<String, ClassRecord> classesByName,
            Map<String, Wrapper> wrappers) {
        List<ConfigurationScanner.Usage> result = new ArrayList<>();
        List<ResourceUsageRecord> resources = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (ClassFileSnapshot.Entry entry :
                classFiles.entries().stream()
                        .sorted(Comparator.comparing(value -> value.path().toString()))
                        .toList()) {
            new ClassReader(entry.bytecode())
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                private String className;
                                private String internalClassName;
                                private final Map<String, String> stringConstants = new HashMap<>();

                                @Override
                                public void visit(
                                        int version,
                                        int access,
                                        String name,
                                        String signature,
                                        String superName,
                                        String[] interfaces) {
                                    className = name.replace('/', '.');
                                    internalClassName = name;
                                }

                                @Override
                                public org.objectweb.asm.FieldVisitor visitField(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        Object value) {
                                    if ((access & Opcodes.ACC_STATIC) != 0
                                            && (access & Opcodes.ACC_FINAL) != 0
                                            && descriptor.equals(Type.getDescriptor(String.class))
                                            && value instanceof String string) {
                                        stringConstants.put(name, string);
                                    }
                                    return null;
                                }

                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    Integer classId = classNameToId.get(className);
                                    if (classId == null) return null;
                                    LookupVisitor visitor =
                                            new LookupVisitor(
                                                    classId,
                                                    className,
                                                    name,
                                                    internalClassName,
                                                    stringConstants,
                                                    classesByName.get(className),
                                                    classesByName,
                                                    result,
                                                    resources,
                                                    unique);
                                    visitor.wrappers = wrappers;
                                    visitor.methodKey = internalClassName + "#" + name + descriptor;
                                    int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                                    Type[] parameters = Type.getArgumentTypes(descriptor);
                                    for (int index = 0; index < parameters.length; index++) {
                                        if (parameters[index].equals(Type.getType(String.class)))
                                            visitor.params.put(slot, new Param(index));
                                        slot += parameters[index].getSize();
                                    }
                                    return visitor;
                                }
                            },
                            ClassReader.SKIP_FRAMES);
        }
        List<ConfigurationScanner.Usage> configurations =
                result.stream()
                        .sorted(
                                Comparator.comparing(ConfigurationScanner.Usage::key)
                                        .thenComparing(ConfigurationScanner.Usage::className)
                                        .thenComparing(
                                                value ->
                                                        value.member() == null
                                                                ? ""
                                                                : value.member()))
                        .toList();
        List<ResourceUsageRecord> resourceUsages =
                resources.stream()
                        .sorted(
                                Comparator.comparing(ResourceUsageRecord::resourcePath)
                                        .thenComparing(ResourceUsageRecord::className)
                                        .thenComparing(ResourceUsageRecord::member))
                        .toList();
        return new ScanResult(configurations, resourceUsages);
    }

    private record Param(int index) {}

    private static final class LookupVisitor extends MethodVisitor {
        private static final Object UNKNOWN = new Object();

        private record ClassLiteral(String className) {}

        private Map<String, Wrapper> wrappers = Map.of();
        private String methodKey;
        private final Map<Integer, Param> params = new HashMap<>();

        private record NormalizedResource(String path, String kind) {}

        private final int classId;
        private final String className;
        private final String method;
        private final String internalClassName;
        private final Map<String, String> stringConstants;
        private final ClassRecord cls;
        private final Map<String, ClassRecord> classesByName;
        private final List<ConfigurationScanner.Usage> target;
        private final List<ResourceUsageRecord> resources;
        private final Set<String> unique;
        private final Deque<Object> stack = new ArrayDeque<>();
        private final Map<Integer, Object> locals = new HashMap<>();

        private LookupVisitor(
                int classId,
                String className,
                String method,
                String internalClassName,
                Map<String, String> stringConstants,
                ClassRecord cls,
                Map<String, ClassRecord> classesByName,
                List<ConfigurationScanner.Usage> target,
                List<ResourceUsageRecord> resources,
                Set<String> unique) {
            super(Opcodes.ASM9);
            this.classId = classId;
            this.className = className;
            this.method = method;
            this.internalClassName = internalClassName;
            this.stringConstants = stringConstants;
            this.cls = cls;
            this.classesByName = classesByName;
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
            if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
                Object value = locals.get(varIndex);
                if (value == null) value = params.get(varIndex);
                stack.push(value == null ? UNKNOWN : value);
            } else if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                params.remove(varIndex);
                Object value = pop();
                if (value == UNKNOWN) locals.remove(varIndex);
                else locals.put(varIndex, value);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.GETSTATIC) {
                stack.push(
                        owner.equals(internalClassName) && stringConstants.containsKey(name)
                                ? stringConstants.get(name)
                                : UNKNOWN);
            } else if (opcode == Opcodes.GETFIELD) {
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
        public void visitMethodInsn(
                int opcode, String owner, String name, String descriptor, boolean isInterface) {
            Type[] argumentTypes = Type.getArgumentTypes(descriptor);
            Object[] arguments = new Object[argumentTypes.length];
            for (int i = argumentTypes.length - 1; i >= 0; i--) arguments[i] = pop();
            Object receiver = opcode == Opcodes.INVOKESTATIC ? null : pop();

            String api = supportedApi(owner, name, descriptor, classesByName);
            int keyIndex = 0;
            Wrapper wrapper = api == null ? wrapper(owner, name, descriptor) : null;
            if (wrapper != null && wrapper.param() < arguments.length) {
                keyIndex = wrapper.param();
                api = wrapper.className() + "#" + wrapper.method() + " -> " + wrapper.api();
            }
            Object keyArgument =
                    api != null && arguments.length > keyIndex ? arguments[keyIndex] : null;
            if (keyArgument instanceof Param param) {
                // This method passes its own parameter on as the key: a wrapper itself.
                wrappers.putIfAbsent(
                        methodKey,
                        new Wrapper(
                                className,
                                method,
                                param.index(),
                                wrapper == null ? api : wrapper.api()));
            }
            // A direct API call records every key, a dynamic one too; a wrapper call only literals.
            if (api != null
                    && arguments.length > keyIndex
                    && (wrapper == null || keyArgument instanceof String)) {
                String key = keyArgument instanceof String value ? value : DYNAMIC_KEY;
                String kind = key.equals(DYNAMIC_KEY) ? "dynamic_config_key" : "config_key";
                String identity = classId + "\n" + method + "\n" + api + "\n" + key;
                if (unique.add(identity)) {
                    target.add(
                            new ConfigurationScanner.Usage(
                                    key,
                                    kind,
                                    classId,
                                    className,
                                    method,
                                    null,
                                    api,
                                    cls == null ? null : cls.sourceFile(),
                                    cls == null ? null : cls.module(),
                                    cls == null ? null : cls.sourceSet()));
                }
            }
            String resourceApi = supportedResourceApi(owner, name, descriptor, classesByName);
            if (resourceApi != null && arguments.length > 0) {
                NormalizedResource normalized =
                        arguments[0] instanceof String value
                                ? normalizeResource(value, resourceApi, className, receiver)
                                : new NormalizedResource(DYNAMIC_KEY, "dynamic_resource");
                String identity =
                        classId + "\n" + method + "\n" + resourceApi + "\n" + normalized.path();
                if (unique.add(identity)) {
                    resources.add(
                            new ResourceUsageRecord(
                                    normalized.path(),
                                    normalized.kind(),
                                    classId,
                                    className,
                                    method,
                                    resourceApi,
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
                case Opcodes.ACONST_NULL,
                        Opcodes.ICONST_M1,
                        Opcodes.ICONST_0,
                        Opcodes.ICONST_1,
                        Opcodes.ICONST_2,
                        Opcodes.ICONST_3,
                        Opcodes.ICONST_4,
                        Opcodes.ICONST_5 ->
                        stack.push(UNKNOWN);
                case Opcodes.DUP -> stack.push(stack.isEmpty() ? UNKNOWN : stack.peek());
                case Opcodes.POP, Opcodes.ARETURN, Opcodes.IRETURN, Opcodes.FRETURN -> pop();
                case Opcodes.POP2, Opcodes.LRETURN, Opcodes.DRETURN -> {
                    pop();
                    pop();
                }
                case Opcodes.RETURN, Opcodes.NOP -> {}
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
            locals.clear();
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name,
                String descriptor,
                org.objectweb.asm.Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
            for (int index = Type.getArgumentTypes(descriptor).length - 1; index >= 0; index--) {
                pop();
            }
            if (Type.getReturnType(descriptor).getSort() != Type.VOID) stack.push(UNKNOWN);
        }

        private Object pop() {
            return stack.isEmpty() ? UNKNOWN : stack.pop();
        }

        /** A known wrapper declared by the owner or the nearest indexed superclass. */
        private Wrapper wrapper(String owner, String name, String descriptor) {
            String type = owner.replace('/', '.');
            Set<String> seen = new java.util.HashSet<>();
            while (type != null && seen.add(type)) {
                Wrapper found = wrappers.get(type.replace('.', '/') + "#" + name + descriptor);
                if (found != null) return found;
                ClassRecord record = classesByName.get(type);
                type = record == null ? null : record.superclass();
            }
            return null;
        }
    }

    /** Spring property-resolver types whose getProperty reads a key; owner names are internal. */
    private static final Set<String> SPRING_PROPERTY_RESOLVERS =
            Set.of(
                    "org/springframework/core/env/Environment",
                    "org/springframework/core/env/ConfigurableEnvironment",
                    "org/springframework/core/env/PropertyResolver",
                    "org/springframework/core/env/ConfigurablePropertyResolver",
                    "org/springframework/core/env/AbstractEnvironment",
                    "org/springframework/core/env/StandardEnvironment",
                    "org/springframework/web/context/support/StandardServletEnvironment",
                    "org/springframework/web/context/ConfigurableWebEnvironment");

    private static String supportedApi(
            String owner, String name, String descriptor, Map<String, ClassRecord> classesByName) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        if (arguments.length == 0 || !arguments[0].equals(Type.getType(String.class))) return null;
        if (owner.equals("java/lang/System") && name.equals("getProperty")) {
            return "java.lang.System#getProperty";
        }
        // Apollo AQ-039: RefreshableConfig holds a ConfigurableEnvironment, a Spring type that is
        // no
        // application class, so assignability through the index never reached Environment.
        if ((SPRING_PROPERTY_RESOLVERS.contains(owner)
                        || isAssignable(
                                owner, "org.springframework.core.env.Environment", classesByName))
                && (name.equals("getProperty") || name.equals("getRequiredProperty"))) {
            return "org.springframework.core.env.Environment#" + name;
        }
        if (isAssignable(owner, "org.eclipse.microprofile.config.Config", classesByName)
                && (name.equals("getValue") || name.equals("getOptionalValue"))) {
            return "org.eclipse.microprofile.config.Config#" + name;
        }
        return null;
    }

    private static String supportedResourceApi(
            String owner, String name, String descriptor, Map<String, ClassRecord> classesByName) {
        Type[] arguments = Type.getArgumentTypes(descriptor);
        if (arguments.length == 0 || !arguments[0].equals(Type.getType(String.class))) return null;
        if ((owner.equals("java/lang/Class") || owner.equals("java/lang/ClassLoader"))
                && (name.equals("getResource") || name.equals("getResourceAsStream"))) {
            return owner.replace('/', '.') + "#" + name;
        }
        if (isAssignable(owner, "org.springframework.core.io.ResourceLoader", classesByName)
                && name.equals("getResource")) {
            return "org.springframework.core.io.ResourceLoader#getResource";
        }
        if (owner.equals("java/util/ResourceBundle") && name.equals("getBundle")) {
            return "java.util.ResourceBundle#getBundle";
        }
        return null;
    }

    private static boolean isAssignable(
            String owner, String target, Map<String, ClassRecord> classesByName) {
        return isAssignable(
                owner.replace('/', '.'), target, classesByName, new java.util.HashSet<>());
    }

    private static boolean isAssignable(
            String owner,
            String target,
            Map<String, ClassRecord> classesByName,
            Set<String> visited) {
        if (owner.equals(target)) return true;
        if (!visited.add(owner)) return false;
        ClassRecord type = classesByName.get(owner);
        if (type == null) return false;
        if (type.superclass() != null
                && isAssignable(type.superclass(), target, classesByName, visited)) return true;
        for (String implemented : type.interfaces()) {
            if (isAssignable(implemented, target, classesByName, visited)) return true;
        }
        return false;
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
            String anchor =
                    receiver instanceof LookupVisitor.ClassLiteral literal
                            ? literal.className()
                            : consumerClass;
            int packageEnd = anchor.lastIndexOf('.');
            if (packageEnd >= 0)
                normalized = anchor.substring(0, packageEnd).replace('.', '/') + "/" + normalized;
        }
        return new LookupVisitor.NormalizedResource(normalized, "resource");
    }
}
