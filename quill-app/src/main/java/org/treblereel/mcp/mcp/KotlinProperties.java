package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.objectweb.asm.Type;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.KotlinDeclarationRecord;

/** Logical Kotlin properties with their exact physical JVM access paths. */
final class KotlinProperties {
    record Binding(ClassRecord owner, String name, String descriptor, boolean field, boolean write) {}
    record Property(ClassRecord owner, String name, String descriptor, List<Binding> bindings) {
        String symbolId() { return SymbolContract.id(owner, "PROPERTY", name, descriptor); }
        void append(ObjectNode node) {
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

    static List<Property> list(Jdbi jdbi, ClassRecord cls) {
        var declarations = IndexReader.findKotlinDeclarations(jdbi, cls.id()).stream()
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
                        && IndexReader.findClassMembers(jdbi, cls.id()).stream().noneMatch(member ->
                            "FIELD".equals(member.kind()) && member.name().equals(declaration.jvmName())
                                    && member.descriptor().equals(declaration.descriptor()))) {
                    var outer = IndexReader.findClassByName(jdbi,
                            cls.className().substring(0, cls.className().length() - "$Companion".length()));
                    if (outer.isPresent() && IndexReader.findClassMembers(jdbi, outer.get().id()).stream()
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
