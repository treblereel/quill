package org.treblereel.mcp.mcp.semantic;

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
public final class SymbolContract {

    private static final String SYMBOL_PREFIX = "quill:symbol:";
    private static final String PREFIX = "quill:symbol:v2:";
    /**
     * v3 is the readable form "quill:symbol:v3:Class~kind~name~descriptor~sourceHash". It is
     * about 60% shorter than v2, which base64-encoded every part including the source path, and
     * it is repeated on every usage, edge and location. '~' and '%' inside a part are
     * percent-encoded; sourceHash is the first 8 hex digits of SHA-256(source path) and only
     * separates equal class names from different source files. v2 ids remain accepted.
     */
    private static final String PREFIX_V3 = "quill:symbol:v3:";
    private static final Set<String> TYPE_KINDS = Set.of(
            "CLASS", "INTERFACE", "ANNOTATION", "ENUM", "RECORD");
    private static final Set<String> MEMBER_KINDS = Set.of(
            "FIELD", "CONSTRUCTOR", "METHOD", "PROPERTY");

    private SymbolContract() {}

    public static String language(String sourceFile, boolean kotlinMetadata) {
        if (kotlinMetadata) return "kotlin";
        String normalized = sourceFile == null ? "" : sourceFile.toLowerCase(Locale.ROOT);
        return normalized.endsWith(".kt") || normalized.endsWith(".kts")
                ? "kotlin" : "java";
    }

    public static String language(ClassRecord cls, List<KotlinDeclarationRecord> declarations) {
        return language(cls.sourceFile(), declarations != null && !declarations.isEmpty());
    }

    public static void appendClass(ObjectNode node, ClassRecord cls,
            List<KotlinDeclarationRecord> declarations) {
        String sourceName = sourceClassName(cls.className(), declarations);
        node.put("symbol_id", id(cls, cls.kind(), cls.className(), ""));
        node.put("language", language(cls, declarations));
        node.put("source_name", sourceName);
        node.put("jvm_name", cls.className());
        appendLocation(node, cls.sourceFile(), cls.sourceLine());
    }

    public static void appendMember(ObjectNode node, ClassRecord cls, ClassMemberRecord member,
            List<KotlinDeclarationRecord> declarations) {
        KotlinDeclarationRecord declaration = KotlinMemberNames
                .declaration(member, declarations).orElse(null);
        String sourceName = declaration == null ? member.name() : declaration.name();
        node.put("symbol_id", id(cls, member.kind(), member.name(), member.descriptor()));
        node.put("language", language(cls, declarations));
        node.put("source_name", sourceName);
        node.put("jvm_name", member.name());
        node.put("jvm_descriptor", member.descriptor());
        appendKotlinRole(node, cls, member, declaration);
        appendMemberLocation(node, cls.sourceFile(), cls.sourceLine());
    }

    /**
     * A member inside a class listing. Identity the listing states once is left out: the id
     * follows {@link #memberIdFormat}, the language and source file are the class's, and the
     * JVM name and descriptor are {@code name} and {@code descriptor}.
     */
    public static void appendListedMember(ObjectNode node, ClassRecord cls, ClassMemberRecord member,
            List<KotlinDeclarationRecord> declarations) {
        KotlinDeclarationRecord declaration = KotlinMemberNames
                .declaration(member, declarations).orElse(null);
        if (declaration != null && !declaration.name().equals(member.name()))
            node.put("source_name", declaration.name());
        appendKotlinRole(node, cls, member, declaration);
    }

    /** {@code {kind}}, {@code {name}} and {@code {descriptor}} are the member's, with ~ and % escaped. */
    public static String memberIdFormat(ClassRecord cls) {
        return PREFIX_V3 + escape(cls.className()) + "~{kind}~{name}~{descriptor}~" + sourceHash(cls.sourceFile());
    }

    private static void appendKotlinRole(ObjectNode node, ClassRecord cls, ClassMemberRecord member,
            KotlinDeclarationRecord declaration) {
        if (declaration != null) {
            node.put("source_kind", declaration.kind().toLowerCase(Locale.ROOT));
            node.put("jvm_role", jvmRole(
                    declaration.kind(), member.kind(), member.descriptor()));
            if ("PROPERTY".equals(declaration.kind()) && !(declaration.delegated() && member.kind().equals("FIELD"))) {
                String descriptor = member.descriptor();
                if (!descriptor.startsWith("(")) descriptor = "()" + descriptor;
                else if (descriptor.endsWith(")V")) {
                    var parameters = org.objectweb.asm.Type.getArgumentTypes(descriptor);
                    if (parameters.length > 0) descriptor = org.objectweb.asm.Type.getMethodDescriptor(
                            parameters[parameters.length - 1],
                            java.util.Arrays.copyOf(parameters, parameters.length - 1));
                }
                node.put("property_symbol_id", id(cls, "PROPERTY", declaration.name(), descriptor));
            }
        }
    }

    public static void append(ObjectNode node, String className, String kind, String jvmName,
            String descriptor, String sourceName, String sourceFile, int sourceLine,
            boolean kotlin) {
        node.put("symbol_id", id(className, kind, jvmName, descriptor, sourceFile));
        node.put("language", language(sourceFile, kotlin));
        node.put("source_name", sourceName == null ? jvmName : sourceName);
        node.put("jvm_name", jvmName);
        if (descriptor != null) node.put("jvm_descriptor", descriptor);
        if (MEMBER_KINDS.contains(kind)) appendMemberLocation(node, sourceFile, sourceLine);
        else appendLocation(node, sourceFile, sourceLine);
    }

    public static String id(String className, String kind, String jvmName, String descriptor) {
        return id(className, kind, jvmName, descriptor, "");
    }

    public static String id(ClassRecord cls, String kind, String jvmName, String descriptor) {
        return id(cls.className(), kind, jvmName, descriptor, cls.sourceFile());
    }

    private static String id(String className, String kind, String jvmName,
            String descriptor, String sourceFile) {
        return PREFIX_V3 + escape(className) + "~" + kind.toLowerCase(Locale.ROOT) + "~"
                + escape(jvmName == null ? "" : jvmName) + "~"
                + escape(descriptor == null ? "" : descriptor) + "~"
                + sourceHash(sourceFile);
    }

    static String sourceHash(String sourceFile) {
        if (sourceFile == null || sourceFile.isBlank()) return "";
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sourceFile.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 4);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String escape(String value) {
        return value.replace("%", "%25").replace("~", "%7E");
    }

    private static String unescape(String value) {
        if (value.matches(".*%(?!25|7E).*")) throw new IllegalArgumentException("Bad escape");
        return value.replace("%7E", "~").replace("%25", "%");
    }

    public static String jvmRole(String semanticKind, String memberKind, String descriptor) {
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

    public static Optional<Reference> parse(String value) {
        if (!isId(value)) return Optional.empty();
        if (value.startsWith(PREFIX_V3)) return parseV3(value.substring(PREFIX_V3.length()));
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
                    || (MEMBER_KINDS.contains(kind) && !"PROPERTY".equals(kind) && descriptor.isBlank())) {
                throw malformed(null);
            }
            return Optional.of(new Reference(
                    className, kind, jvmName, descriptor, sourceFile, sourceHash(sourceFile)));
        } catch (ParseException error) {
            throw error;
        } catch (IllegalArgumentException error) {
            throw malformed(error);
        }
    }

    private static Optional<Reference> parseV3(String body) {
        String[] parts = body.split("~", -1);
        if (parts.length != 5) throw malformed(null);
        try {
            String className = unescape(parts[0]);
            String kind = parts[1].toUpperCase(Locale.ROOT);
            String jvmName = unescape(parts[2]);
            String descriptor = unescape(parts[3]);
            String hash = parts[4];
            if (className.isBlank() || jvmName.isBlank() || !hash.matches("([0-9a-f]{8})?")
                    || (!TYPE_KINDS.contains(kind) && !MEMBER_KINDS.contains(kind))
                    || (MEMBER_KINDS.contains(kind) && !"PROPERTY".equals(kind) && descriptor.isBlank())) {
                throw malformed(null);
            }
            return Optional.of(new Reference(className, kind, jvmName, descriptor, "", hash));
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
        if (sourceLine > 0) location.put("line", sourceLine);
        else location.putNull("line");
        location.put("basis", sourceLine > 0 ? "indexed_class" : "unknown");
    }

    private static void appendMemberLocation(
            ObjectNode node, String sourceFile, int classLine) {
        ObjectNode location = node.putObject("location");
        if (sourceFile == null) location.putNull("path");
        else location.put("path", sourceFile);
        location.putNull("line");
        location.put("basis", "unknown");
        if (classLine > 0) location.put("class_declaration_line", classLine);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public record Reference(String className, String kind, String jvmName, String descriptor,
            String sourceFile, String sourceHash) {
        Reference(String className, String kind, String jvmName, String descriptor, String sourceFile) {
            this(className, kind, jvmName, descriptor, sourceFile, SymbolContract.sourceHash(sourceFile));
        }

        /** Whether a declaration from {@code candidateSource} is the one this id names. */
        public boolean matchesSource(String candidateSource) {
            if (sourceHash == null || sourceHash.isBlank()) return true;
            return sourceHash.equals(SymbolContract.sourceHash(candidateSource));
        }

        String resolutionTarget() {
            return sourceFile == null || sourceFile.isBlank() ? className : sourceFile;
        }
    }

    public static final class ParseException extends IllegalArgumentException {
        private final String errorCode;

        ParseException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        ParseException(String errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
