package org.treblereel.mcp.mcp;

import java.util.List;
import java.util.Optional;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.KotlinDeclarationRecord;

/** Resolves Kotlin source-level function names onto their indexed JVM methods. */
final class KotlinMemberNames {

    private KotlinMemberNames() {}

    static boolean matches(String requested, ClassMemberRecord member,
            List<KotlinDeclarationRecord> declarations) {
        if (member.name().equals(requested)) return true;
        return declaration(member, declarations)
                .map(value -> value.name().equals(requested))
                .orElse(false);
    }

    static Optional<KotlinDeclarationRecord> declaration(ClassMemberRecord member,
            List<KotlinDeclarationRecord> declarations) {
        if (!"METHOD".equals(member.kind())) return Optional.empty();
        return declarations.stream()
                .filter(value -> "FUNCTION".equals(value.kind()))
                .filter(value -> member.name().equals(value.jvmName()))
                .filter(value -> member.descriptor().equals(value.descriptor()))
                .findFirst();
    }
}
