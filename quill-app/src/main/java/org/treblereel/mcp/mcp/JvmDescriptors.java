package org.treblereel.mcp.mcp;

import org.objectweb.asm.Type;
import org.treblereel.mcp.model.ClassMemberRecord;

/** Converts indexed erased Java type names to JVM descriptors when unambiguous. */
final class JvmDescriptors {

    private JvmDescriptors() {}

    static boolean matchesSignature(ClassMemberRecord member, String signature) {
        if (signature.equals(member.signature()) || signature.equals(member.descriptor())) return true;
        if (!member.kind().equals("METHOD") && !member.kind().equals("CONSTRUCTOR")) return false;
        String descriptor = methodDescriptor(member);
        if (signature.equals(descriptor)) return true;
        int open = signature.indexOf('(');
        if (open < 0 || !signature.endsWith(")")) return false;
        String name = signature.substring(0, open).trim();
        if (!name.isEmpty() && !name.equals(member.name())
                && !(name.equals("<init>") && member.kind().equals("CONSTRUCTOR"))) return false;
        String parameters = signature.substring(open + 1, signature.length() - 1).trim();
        String[] requested = parameters.isEmpty() ? new String[0] : parameters.split(",", -1);
        if (descriptor == null) return false;
        Type[] actual = Type.getArgumentTypes(descriptor);
        if (requested.length != actual.length) return false;
        for (int i = 0; i < requested.length; i++) {
            String type = requested[i].trim().replace("...", "[]");
            String indexed = actual[i].getClassName().replace('$', '.');
            if (!type.equals(indexed) && (type.contains(".")
                    || !type.equals(indexed.substring(indexed.lastIndexOf('.') + 1)))) return false;
        }
        // The caller retains every matching candidate, so simple names never pick an arbitrary overload.
        return true;
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
