package org.treblereel.mcp.model;

import java.util.List;

/** Source types from Kotlin metadata; type parameters refer to metadata IDs, not erased JVM types. */
public record KotlinDeclarationTypes(Type result, Type receiver, List<Parameter> parameters,
        List<TypeParameter> typeParameters, List<Type> contextReceivers) {
    public KotlinDeclarationTypes {
        parameters = List.copyOf(parameters);
        typeParameters = List.copyOf(typeParameters);
        contextReceivers = List.copyOf(contextReceivers);
    }
    public record Type(String classifierKind, String classifier, boolean nullable,
            boolean definitelyNonNull, boolean suspend, List<Projection> arguments,
            Type outer, Type abbreviated, Type flexibleUpper, String flexibilityId) {
        public Type {
            if (!List.of("class", "type_alias", "type_parameter").contains(classifierKind) || classifier == null || classifier.isBlank())
                throw new IllegalArgumentException("Missing classifier");
            arguments = List.copyOf(arguments);
        }
    }
    public record Projection(String variance, Type type) {
        public Projection {
            if (!List.of("STAR", "IN", "OUT", "INVARIANT").contains(variance)
                    || ("STAR".equals(variance) ? type != null : type == null))
                throw new IllegalArgumentException("Invalid type projection");
        }
    }
    public record Parameter(String name, Type type, Type varargElement) {
        public Parameter { if (name == null || name.isBlank() || type == null) throw new IllegalArgumentException("Invalid parameter"); }
    }
    public record TypeParameter(int id, String name, String variance, boolean reified, List<Type> bounds) {
        public TypeParameter {
            if (id < 0 || name == null || name.isBlank() || !List.of("IN", "OUT", "INVARIANT").contains(variance))
                throw new IllegalArgumentException("Invalid type parameter");
            bounds = List.copyOf(bounds);
        }
    }
}
