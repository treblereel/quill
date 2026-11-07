package org.treblereel.mcp.mcp;

import org.objectweb.asm.Type;
import org.treblereel.mcp.model.ClassMemberRecord;

/** Converts indexed erased Java type names to JVM descriptors when unambiguous. */
final class JvmDescriptors {

    private JvmDescriptors() {}

    static boolean matchesSignature(ClassMemberRecord member, String selector) {
        if (selector == null || selector.isBlank()) return true;
        String value = selector.strip();
        if (value.equals(member.signature()) || value.equals(member.descriptor())) return true;
        if (!"METHOD".equals(member.kind()) && !"CONSTRUCTOR".equals(member.kind())) return false;
        String descriptor = methodDescriptor(member);
        if (value.equals(descriptor)) return true;
        if (descriptor == null) return false;
        if (value.startsWith(member.name() + "(")) value = value.substring(member.name().length());
        if ("CONSTRUCTOR".equals(member.kind()) && value.startsWith("<init>(")) {
            value = value.substring("<init>".length());
        }
        String parameters;
        int close = value.lastIndexOf(')');
        if (value.startsWith("(") && close > 0) {
            String returned = value.substring(close + 1).strip();
            if (!returned.isEmpty()) {
                if (!returned.startsWith(":")) return false;
                if (!typeMatches(returned.substring(1), Type.getReturnType(descriptor))) return false;
            }
            parameters = value.substring(1, close);
        } else {
            if (value.contains("(") || value.contains(")")) return false;
            parameters = value;
        }
        parameters = eraseGenerics(parameters);
        String[] requested = parameters.isBlank() ? new String[0] : parameters.split(",", -1);
        Type[] actual = Type.getArgumentTypes(descriptor);
        if (requested.length != actual.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (!typeMatches(requested[i], actual[i])) return false;
        }
        return true;
    }

    /** Callers still reject multiple matching overloads; a short type never selects one arbitrarily. */
    private static boolean typeMatches(String selector, Type actual) {
        String wanted = eraseGenerics(selector).strip().replace("...", "[]").replace('$', '.');
        String qualified = actual.getClassName().replace('$', '.');
        return wanted.equals(qualified) || (wanted.indexOf('.') < 0
                && wanted.equals(qualified.substring(qualified.lastIndexOf('.') + 1)));
    }

    static String methodDescriptor(ClassMemberRecord member) {
        if (member.descriptor() != null && !member.descriptor().isBlank()) {
            return member.descriptor();
        }
        Type returnType = asmType(member.typeName());
        if (returnType == null) return null;
        Type[] parameters = new Type[member.parameterTypes().size()];
        for (int i = 0; i < parameters.length; i++) {
            parameters[i] = asmType(member.parameterTypes().get(i));
            if (parameters[i] == null) return null;
        }
        return Type.getMethodDescriptor(returnType, parameters);
    }

    static String fieldDescriptor(ClassMemberRecord member) {
        if (member.descriptor() != null && !member.descriptor().isBlank()) {
            return member.descriptor();
        }
        Type type = asmType(member.typeName());
        return type == null || type.getSort() == Type.VOID ? null : type.getDescriptor();
    }

    private static Type asmType(String typeName) {
        if (typeName == null || typeName.isBlank()) return null;
        String erased = eraseGenerics(typeName.trim());
        int dimensions = 0;
        while (erased.endsWith("[]")) {
            dimensions++;
            erased = erased.substring(0, erased.length() - 2);
        }
        String descriptor = switch (erased) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> {
                if (!erased.contains(".") || erased.contains("?") || erased.contains(" ")) {
                    yield null;
                }
                yield "L" + erased.replace('.', '/') + ";";
            }
        };
        if (descriptor == null || (dimensions > 0 && "V".equals(descriptor))) return null;
        return Type.getType("[".repeat(dimensions) + descriptor);
    }

    private static String eraseGenerics(String typeName) {
        StringBuilder result = new StringBuilder(typeName.length());
        int depth = 0;
        for (int i = 0; i < typeName.length(); i++) {
            char value = typeName.charAt(i);
            if (value == '<') depth++;
            else if (value == '>') depth--;
            else if (depth == 0) result.append(value);
        }
        return result.toString();
    }
}
