package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.KotlinDeclarationRecord;

/** Shared, source-oriented identity fields for symbols returned by MCP tools. */
final class SymbolContract {

    private static final String PREFIX = "quill:symbol:v1:";

    private SymbolContract() {}

    static String language(String sourceFile, boolean kotlinMetadata) {
        if (kotlinMetadata) return "kotlin";
        String normalized = sourceFile == null ? "" : sourceFile.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".kt") || normalized.endsWith(".kts")
                ? "kotlin" : "java";
    }

    static String language(ClassRecord cls, List<KotlinDeclarationRecord> declarations) {
        return language(cls.sourceFile(), declarations != null && !declarations.isEmpty());
    }

    static void appendClass(ObjectNode node, ClassRecord cls,
            List<KotlinDeclarationRecord> declarations) {
        String sourceName = sourceClassName(cls.className(), declarations);
        node.put("symbol_id", id(cls.className(), cls.kind(), cls.className(), ""));
        node.put("language", language(cls, declarations));
        node.put("source_name", sourceName);
        node.put("jvm_name", cls.className());
        appendLocation(node, cls.sourceFile(), cls.sourceLine());
    }

    static void appendMember(ObjectNode node, ClassRecord cls, ClassMemberRecord member,
            List<KotlinDeclarationRecord> declarations) {
        KotlinDeclarationRecord declaration = KotlinMemberNames
                .declaration(member, declarations).orElse(null);
        String sourceName = declaration == null ? member.name() : declaration.name();
        node.put("symbol_id", id(
                cls.className(), member.kind(), member.name(), member.descriptor()));
        node.put("language", language(cls, declarations));
        node.put("source_name", sourceName);
        node.put("jvm_name", member.name());
        node.put("jvm_descriptor", member.descriptor());
        appendLocation(node, cls.sourceFile(), cls.sourceLine());
    }

    static void append(ObjectNode node, String className, String kind, String jvmName,
            String descriptor, String sourceName, String sourceFile, int sourceLine,
            boolean kotlin) {
        node.put("symbol_id", id(className, kind, jvmName, descriptor));
        node.put("language", language(sourceFile, kotlin));
        node.put("source_name", sourceName == null ? jvmName : sourceName);
        node.put("jvm_name", jvmName);
        if (descriptor != null) node.put("jvm_descriptor", descriptor);
        appendLocation(node, sourceFile, sourceLine);
    }

    static String id(String className, String kind, String jvmName, String descriptor) {
        return PREFIX + encode(className) + ":" + kind.toLowerCase(Locale.ROOT) + ":"
                + encode(jvmName == null ? "" : jvmName) + ":"
                + encode(descriptor == null ? "" : descriptor);
    }

    static boolean isId(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    static Optional<Reference> parse(String value) {
        if (!isId(value)) return Optional.empty();
        String[] parts = value.substring(PREFIX.length()).split(":", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Malformed symbol_id");
        }
        try {
            String className = decode(parts[0]);
            String kind = parts[1].toUpperCase(Locale.ROOT);
            String jvmName = decode(parts[2]);
            String descriptor = decode(parts[3]);
            if (className.isBlank() || kind.isBlank()) {
                throw new IllegalArgumentException("Malformed symbol_id");
            }
            return Optional.of(new Reference(className, kind, jvmName, descriptor));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Malformed symbol_id", error);
        }
    }

    private static String sourceClassName(
            String className, List<KotlinDeclarationRecord> declarations) {
        if (declarations != null) {
            String semanticName = declarations.stream()
                    .filter(value -> !"FUNCTION".equals(value.kind())
                            && !"PROPERTY".equals(value.kind()))
                    .map(KotlinDeclarationRecord::name)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst().orElse(null);
            if (semanticName != null) return simpleName(semanticName);
        }
        return simpleName(className);
    }

    private static String simpleName(String name) {
        int separator = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
        return separator < 0 ? name : name.substring(separator + 1);
    }

    private static void appendLocation(
            ObjectNode node, String sourceFile, int sourceLine) {
        ObjectNode location = node.putObject("location");
        if (sourceFile == null) location.putNull("path");
        else location.put("path", sourceFile);
        location.put("line", sourceLine);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    record Reference(String className, String kind, String jvmName, String descriptor) {}
}
