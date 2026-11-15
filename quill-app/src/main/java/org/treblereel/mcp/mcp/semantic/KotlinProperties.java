package org.treblereel.mcp.mcp.semantic;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.treblereel.mcp.db.ReadQueryContext;
import org.objectweb.asm.Type;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.KotlinDeclarationRecord;

/** Logical Kotlin properties with their exact physical JVM access paths. */
public final class KotlinProperties {
    public record Binding(ClassRecord owner, String name, String descriptor, boolean field, boolean write) {}
    public record Property(ClassRecord owner, String name, String descriptor, List<Binding> bindings) {
        public String symbolId() { return SymbolContract.id(owner, "PROPERTY", name, descriptor); }
        public void append(ObjectNode node) {
            SymbolContract.append(node, owner.className(), "PROPERTY", name, descriptor,
                    name, owner.sourceFile(), owner.sourceLine(), true);
            node.put("kind", "property");
            node.put("declaring_class", owner.className());
            node.put("name", name);
            node.put("source_kind", "property");
            node.put("semantic_model", "kotlin_metadata");
            var paths = node.putArray("jvm_bindings");
            for (Binding binding : bindings) {
                var path = paths.addObject();
                path.put("class", binding.owner().className());
                path.put("name", binding.name());
                path.put("descriptor", binding.descriptor());
                path.put("role", binding.field() ? "field" : binding.write() ? "setter" : "getter");
            }
        }
    }

    public static List<Property> list(ReadQueryContext reader, ClassRecord cls) {
        var declarations = reader.symbols.findKotlinDeclarations(cls.id()).stream()
                .filter(value -> "PROPERTY".equals(value.kind())).toList();
        var groups = new LinkedHashMap<String, List<KotlinDeclarationRecord>>();
        for (var declaration : declarations) {
            String signature = signature(declaration);
            // A delegated backing field holds the delegate, not the property's value.
            if (declaration.delegated() && !declaration.descriptor().startsWith("(")) continue;
            groups.computeIfAbsent(declaration.name() + "\n" + signature,
                    key -> new ArrayList<>()).add(declaration);
        }
        var properties = new ArrayList<Property>();
        for (var group : groups.values()) {
            var first = group.getFirst();
            var bindings = new ArrayList<Binding>();
            for (var declaration : group) {
                if (declaration.jvmName() == null || declaration.descriptor().isBlank()) continue;
                boolean field = !declaration.descriptor().startsWith("(");
                ClassRecord owner = cls;
                if (field && cls.className().endsWith("$Companion")
                        && reader.symbols.findClassMembers(cls.id()).stream().noneMatch(member ->
                            "FIELD".equals(member.kind()) && member.name().equals(declaration.jvmName())
                                    && member.descriptor().equals(declaration.descriptor()))) {
                    var outer = reader.symbols.findClassByName(cls.className().substring(0, cls.className().length() - "$Companion".length()));
                    if (outer.isPresent() && reader.symbols.findClassMembers(outer.get().id()).stream()
                            .anyMatch(member -> "FIELD".equals(member.kind())
                                && member.name().equals(declaration.jvmName())
                                && member.descriptor().equals(declaration.descriptor()))) owner = outer.get();
                }
                var binding = new Binding(owner, declaration.jvmName(), declaration.descriptor(),
                        field, !field && Type.getReturnType(declaration.descriptor()).equals(Type.VOID_TYPE));
                if (!bindings.contains(binding)) bindings.add(binding);
            }
            properties.add(new Property(cls, first.name(), signature(first), List.copyOf(bindings)));
        }
        return List.copyOf(properties);
    }

    private static String signature(KotlinDeclarationRecord declaration) {
        String descriptor = declaration.descriptor();
        if (descriptor.isBlank()) return "";
        if (!descriptor.startsWith("(")) return "()" + descriptor;
        Type result = Type.getReturnType(descriptor);
        if (!result.equals(Type.VOID_TYPE)) return descriptor;
        Type[] parameters = Type.getArgumentTypes(descriptor);
        if (parameters.length == 0) return descriptor;
        return Type.getMethodDescriptor(parameters[parameters.length - 1],
                java.util.Arrays.copyOf(parameters, parameters.length - 1));
    }
}
