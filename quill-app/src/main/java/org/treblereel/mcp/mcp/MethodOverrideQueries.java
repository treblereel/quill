package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.KotlinDeclarationRecord;

/** Finds declared method overrides in indexed descendants. */
final class MethodOverrideQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String findMethodOverrides(Jdbi jdbi, String target, String method,
            String signature, boolean transitive, int limit, int offset) {
        try {
            SymbolContract.Reference reference = SymbolContract.parse(target).orElse(null);
            if (reference != null) {
                if (!"METHOD".equals(reference.kind())) {
                    return errorResponse("INVALID_SYMBOL_KIND",
                            "symbol_id must identify a method");
                }
                target = reference.resolutionTarget();
                method = reference.jvmName();
                signature = reference.descriptor();
            }
        } catch (SymbolContract.ParseException error) {
            return errorResponse(error.errorCode(), error.getMessage());
        }
        if (method == null || method.isBlank()) {
            return errorResponse("MISSING_METHOD", "Method name or symbol_id must be provided");
        }
        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord base = lookup.cls();
        String methodName = method.trim();
        String requestedSignature = signature == null || signature.isBlank()
                ? null : signature.trim();
        List<KotlinDeclarationRecord> kotlinDeclarations =
                IndexReader.findKotlinDeclarations(jdbi, base.id());
        List<ClassMemberRecord> namedDeclarations = IndexReader.findClassMembers(
                jdbi, base.id()).stream()
                .filter(member -> "METHOD".equals(member.kind()))
                .filter(member -> KotlinMemberNames.matches(
                        methodName, member, kotlinDeclarations))
                .toList();
        List<ClassMemberRecord> baseDeclarations = namedDeclarations.stream()
                .filter(member -> requestedSignature == null
                        || requestedSignature.equals(member.signature())
                        || requestedSignature.equals(member.descriptor()))
                .toList();
        if (baseDeclarations.isEmpty()) {
            ObjectNode error = JSON.createObjectNode();
            appendError(error, "METHOD_NOT_FOUND", requestedSignature == null
                    ? "Method not found" : "Method signature not found");
            error.put("class", base.className());
            error.put("method", methodName);
            error.set("candidates", JSON.valueToTree(namedDeclarations.stream()
                    .map(ClassMemberRecord::signature).toList()));
            return error.toString();
        }

        List<ClassRecord> classes = IndexReader.findAllClasses(jdbi);
        Map<String, DescendantPath> paths = descendantPaths(classes, base.className(), transitive);
        List<ClassRecord> descendants = classes.stream()
                .filter(candidate -> paths.containsKey(candidate.className()))
                .toList();
        Map<Integer, List<ClassMemberRecord>> members = IndexReader.findClassMembers(
                jdbi, descendants.stream().map(ClassRecord::id).toList());
        List<OverrideMatch> matches = new ArrayList<>();
        for (ClassRecord descendant : descendants) {
            for (ClassMemberRecord candidate : members.getOrDefault(
                    descendant.id(), List.of())) {
                if (!"METHOD".equals(candidate.kind()) || hasModifier(candidate, "static")
                        || hasModifier(candidate, "private")) continue;
                for (ClassMemberRecord declaration : baseDeclarations) {
                    if (!declaration.name().equals(candidate.name())
                            || !overridable(declaration, base, descendant)
                            || !declaration.parameterTypes().equals(candidate.parameterTypes())) {
                        continue;
                    }
                    matches.add(new OverrideMatch(descendant, candidate, declaration,
                            paths.get(descendant.className())));
                }
            }
        }
        matches.sort(Comparator
                .comparingInt((OverrideMatch match) -> match.path().distance())
                .thenComparing(match -> match.owner().className())
                .thenComparing(match -> match.member().signature()));
        int from = Math.min(offset, matches.size());
        int to = (int) Math.min((long) from + limit, matches.size());
        List<OverrideMatch> page = matches.subList(from, to);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", base.className());
        root.put("method", methodName);
        KotlinDeclarationRecord kotlinDeclaration = baseDeclarations.stream()
                .map(member -> KotlinMemberNames.declaration(member, kotlinDeclarations)
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        if (kotlinDeclaration != null) {
            root.put("language", "kotlin");
            root.put("kotlin_name", kotlinDeclaration.name());
            root.set("jvm_names", JSON.valueToTree(baseDeclarations.stream()
                    .map(ClassMemberRecord::name).distinct().sorted().toList()));
        }
        if (requestedSignature == null) root.putNull("signature");
        else root.put("signature", requestedSignature);
        root.put("transitive", transitive);
        ArrayNode declarations = root.putArray("base_declarations");
        for (ClassMemberRecord declaration : baseDeclarations) {
            ObjectNode node = declarations.addObject();
            SymbolContract.appendMember(node, base, declaration, kotlinDeclarations);
            node.put("signature", declaration.signature());
            node.set("parameters", JSON.valueToTree(declaration.parameterTypes()));
            node.put("return_type", declaration.typeName());
            node.put("modifiers", declaration.modifiers());
            node.put("overridable", overridable(declaration, base, base));
            String reason = nonOverridableReason(declaration);
            if (reason == null) node.putNull("non_overridable_reason");
            else node.put("non_overridable_reason", reason);
        }
        ArrayNode limitations = root.putArray("limitations");
        limitations.add("Override matching uses erased parameter types from indexed bytecode");
        limitations.add("Compiler bridge methods are excluded; some generic specializations may require source analysis");

        ArrayNode overrides = root.putArray("overrides");
        int naiveTokens = base.sourceTokens();
        Set<Integer> countedClasses = new HashSet<>();
        countedClasses.add(base.id());
        for (OverrideMatch match : page) {
            ObjectNode node = overrides.addObject();
            SymbolContract.appendMember(node, match.owner(), match.member(),
                    IndexReader.findKotlinDeclarations(jdbi, match.owner().id()));
            node.put("class", match.owner().className());
            node.put("signature", match.member().signature());
            node.set("parameters", JSON.valueToTree(match.member().parameterTypes()));
            node.put("return_type", match.member().typeName());
            node.put("modifiers", match.member().modifiers());
            node.put("base_signature", match.baseDeclaration().signature());
            node.put("distance", match.path().distance());
            node.put("direct", match.path().distance() == 1);
            node.set("hierarchy_path", JSON.valueToTree(match.path().classes()));
            node.put("source", match.owner().sourceFile() + ":" + match.owner().sourceLine());
            node.put("origin", match.owner().origin());
            if (match.owner().module() == null) node.putNull("module");
            else node.put("module", match.owner().module());
            if (countedClasses.add(match.owner().id())) {
                naiveTokens += match.owner().sourceTokens();
            }
        }
        appendPage(root, page.size(), matches.size(), limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static Map<String, DescendantPath> descendantPaths(
            List<ClassRecord> classes, String target, boolean transitive) {
        Map<String, List<ClassRecord>> children = new LinkedHashMap<>();
        for (ClassRecord candidate : classes) {
            if (candidate.superclass() != null) {
                children.computeIfAbsent(candidate.superclass(), ignored -> new ArrayList<>())
                        .add(candidate);
            }
            for (String implemented : candidate.interfaces()) {
                children.computeIfAbsent(implemented, ignored -> new ArrayList<>()).add(candidate);
            }
        }
        children.values().forEach(values -> values.sort(
                Comparator.comparing(ClassRecord::className)));

        Map<String, DescendantPath> paths = new LinkedHashMap<>();
        paths.put(target, new DescendantPath(0, List.of(target)));
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(target);
        while (!pending.isEmpty()) {
            String parentName = pending.removeFirst();
            DescendantPath parent = paths.get(parentName);
            if (!transitive && parent.distance() >= 1) continue;
            for (ClassRecord candidate : children.getOrDefault(parentName, List.of())) {
                if (paths.containsKey(candidate.className())) continue;
                List<String> path = new ArrayList<>(parent.classes());
                path.add(candidate.className());
                paths.put(candidate.className(), new DescendantPath(
                        parent.distance() + 1, List.copyOf(path)));
                pending.addLast(candidate.className());
            }
        }
        paths.remove(target);
        return paths;
    }

    private static boolean overridable(
            ClassMemberRecord declaration, ClassRecord base, ClassRecord descendant) {
        if (nonOverridableReason(declaration) != null) return false;
        if (hasVisibility(declaration)) return true;
        return packageName(base.className()).equals(packageName(descendant.className()));
    }

    private static String nonOverridableReason(ClassMemberRecord declaration) {
        if (hasModifier(declaration, "private")) return "private";
        if (hasModifier(declaration, "static")) return "static";
        if (hasModifier(declaration, "final")) return "final";
        return null;
    }

    private static boolean hasVisibility(ClassMemberRecord member) {
        return hasModifier(member, "public") || hasModifier(member, "protected")
                || hasModifier(member, "private");
    }

    private static boolean hasModifier(ClassMemberRecord member, String modifier) {
        if (member.modifiers() == null || member.modifiers().isBlank()) return false;
        return List.of(member.modifiers().split("\\s+")).contains(modifier);
    }

    private static String packageName(String className) {
        int separator = className.lastIndexOf('.');
        return separator < 0 ? "" : className.substring(0, separator);
    }

    private record DescendantPath(int distance, List<String> classes) {}

    private record OverrideMatch(
            ClassRecord owner,
            ClassMemberRecord member,
            ClassMemberRecord baseDeclaration,
            DescendantPath path) {}
}
