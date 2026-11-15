package org.treblereel.mcp.mcp.semantic;

import java.util.List;
import java.util.Optional;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.KotlinDeclarationRecord;

/** Resolves Kotlin source-level function names onto their indexed JVM methods. */
public final class KotlinMemberNames {

    private KotlinMemberNames() {}

    public static boolean matches(String requested, ClassMemberRecord member,
            List<KotlinDeclarationRecord> declarations) {
        if (member.name().equals(requested)) return true;
        return declaration(member, declarations)
                .map(value -> value.name().equals(requested))
                .orElse(false);
    }

    public static Optional<KotlinDeclarationRecord> declaration(ClassMemberRecord member,
            List<KotlinDeclarationRecord> declarations) {
        return declarations.stream()
                .filter(value -> "FUNCTION".equals(value.kind())
                        || "PROPERTY".equals(value.kind()) || "CONSTRUCTOR".equals(value.kind()))
                .filter(value -> ("CONSTRUCTOR".equals(member.kind()) ? "<init>" : member.name()).equals(value.jvmName()))
                .filter(value -> value.descriptor() == null || value.descriptor().isBlank()
                        || member.descriptor().equals(value.descriptor()))
                .findFirst();
    }
}
