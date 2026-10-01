package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.KotlinDeclarationRecord;

/** Shared, source-oriented identity fields for symbols returned by MCP tools. */
final class SymbolContract {

    private static final String SYMBOL_PREFIX = "quill:symbol:";
    private static final String PREFIX = "quill:symbol:v2:";
    private static final Set<String> TYPE_KINDS = Set.of(
            "CLASS", "INTERFACE", "ANNOTATION", "ENUM", "RECORD");
    private static final Set<String> MEMBER_KINDS = Set.of(
            "FIELD", "CONSTRUCTOR", "METHOD");

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
        node.put("symbol_id", id(cls, cls.kind(), cls.className(), ""));
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
        node.put("symbol_id", id(cls, member.kind(), member.name(), member.descriptor()));
        node.put("language", language(cls, declarations));
        node.put("source_name", sourceName);
        node.put("jvm_name", member.name());
        node.put("jvm_descriptor", member.descriptor());
        if (declaration != null) {
            node.put("source_kind", declaration.kind().toLowerCase(Locale.ROOT));
            node.put("jvm_role", jvmRole(
                    declaration.kind(), member.kind(), member.descriptor()));
        }
        appendLocation(node, cls.sourceFile(), cls.sourceLine());
    }

    static void append(ObjectNode node, String className, String kind, String jvmName,
            String descriptor, String sourceName, String sourceFile, int sourceLine,
            boolean kotlin) {
        node.put("symbol_id", id(className, kind, jvmName, descriptor, sourceFile));
        node.put("language", language(sourceFile, kotlin));
        node.put("source_name", sourceName == null ? jvmName : sourceName);
        node.put("jvm_name", jvmName);
        if (descriptor != null) node.put("jvm_descriptor", descriptor);
        appendLocation(node, sourceFile, sourceLine);
    }

    static String id(String className, String kind, String jvmName, String descriptor) {
        return id(className, kind, jvmName, descriptor, "");
    }

    static String id(ClassRecord cls, String kind, String jvmName, String descriptor) {
        return id(cls.className(), kind, jvmName, descriptor, cls.sourceFile());
    }

    private static String id(String className, String kind, String jvmName,
            String descriptor, String sourceFile) {
        return PREFIX + encode(className) + ":" + kind.toLowerCase(Locale.ROOT) + ":"
                + encode(jvmName == null ? "" : jvmName) + ":"
                + encode(descriptor == null ? "" : descriptor) + ":"
                + encode(sourceFile == null ? "" : sourceFile);
    }

    static String jvmRole(String semanticKind, String memberKind, String descriptor) {
        if (!"PROPERTY".equals(semanticKind)) {
            return "FUNCTION".equals(semanticKind) ? "function" : "type";
        }
        if ("FIELD".equals(memberKind)) return "property_field";
        return descriptor != null && descriptor.endsWith(")V")
                ? "property_setter" : "property_getter";
    }

    static boolean isId(String value) {
        return value != null && value.startsWith(SYMBOL_PREFIX);
    }

    static Optional<Reference> parse(String value) {
        if (!isId(value)) return Optional.empty();
        if (!value.startsWith(PREFIX)) {
            throw new ParseException("UNSUPPORTED_SYMBOL_ID_VERSION",
                    "Unsupported symbol_id version");
        }
        String[] parts = value.substring(PREFIX.length()).split(":", -1);
        if (parts.length != 5) {
            throw malformed(null);
        }
        try {
            String className = decode(parts[0]);
            String kind = parts[1].toUpperCase(Locale.ROOT);
            String jvmName = decode(parts[2]);
            String descriptor = decode(parts[3]);
            String sourceFile = decode(parts[4]);
            if (className.isBlank() || jvmName.isBlank()
                    || (!TYPE_KINDS.contains(kind) && !MEMBER_KINDS.contains(kind))
                    || (MEMBER_KINDS.contains(kind) && descriptor.isBlank())) {
                throw malformed(null);
            }
            return Optional.of(new Reference(
                    className, kind, jvmName, descriptor, sourceFile));
        } catch (ParseException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw malformed(error);
        }
    }

    private static ParseException malformed(Throwable cause) {
        return new ParseException("MALFORMED_SYMBOL_ID", "Malformed symbol_id", cause);
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

    record Reference(String className, String kind, String jvmName, String descriptor,
            String sourceFile) {
        String resolutionTarget() {
            return sourceFile == null || sourceFile.isBlank() ? className : sourceFile;
        }
    }

    static final class ParseException extends IllegalArgumentException {
        private final String errorCode;

        ParseException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        ParseException(String errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        String errorCode() {
            return errorCode;
        }
    }
}
