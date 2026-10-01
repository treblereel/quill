package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.FileRecord;

/** Resolves the class identifiers accepted by MCP tools into one current index entity. */
final class ClassTargetResolver {

    record Lookup(ClassRecord cls, String errorCode, String error, List<ClassRecord> candidates,
            List<FileRecord> fileCandidates) {
        static Lookup found(ClassRecord cls) {
            return new Lookup(cls, null, null, List.of(), List.of());
        }

        static Lookup error(String code, String message, List<ClassRecord> candidates,
                List<FileRecord> fileCandidates) {
            return new Lookup(null, code, message, candidates, fileCandidates);
        }

        boolean found() {
            return cls != null;
        }
    }

    private ClassTargetResolver() {}

    static Lookup resolve(Jdbi jdbi, String target) {
        if (target == null || target.isBlank()) {
            return Lookup.error("MISSING_TARGET", "Class target or symbol_id is required",
                    List.of(), List.of());
        }
        try {
            String requested = target;
            target = SymbolContract.parse(requested)
                    .map(SymbolContract.Reference::className).orElse(requested);
        } catch (IllegalArgumentException error) {
            return Lookup.error("MALFORMED_SYMBOL_ID", "Malformed symbol_id",
                    List.of(), List.of());
        }
        var exactNames = IndexReader.findClassesByName(jdbi, target);
        if (exactNames.size() == 1) return Lookup.found(exactNames.getFirst());
        if (exactNames.size() > 1) {
            return Lookup.error("AMBIGUOUS_CLASS", "Ambiguous class context",
                    exactNames, List.of());
        }

        var exactPath = IndexReader.findClassByPath(jdbi, target);
        if (exactPath.isPresent()) return Lookup.found(exactPath.get());

        if (!target.contains(".")) {
            List<ClassRecord> shortNameCandidates =
                    IndexReader.findClassesByShortName(jdbi, target);
            if (shortNameCandidates.size() == 1) {
                return Lookup.found(shortNameCandidates.getFirst());
            }
            if (shortNameCandidates.size() > 1) {
                return Lookup.error("AMBIGUOUS_CLASS", "Ambiguous class name",
                        shortNameCandidates, List.of());
            }
        }

        String basename = target.replace('\\', '/');
        basename = basename.substring(basename.lastIndexOf('/') + 1);
        if (basename.endsWith(".java") || basename.endsWith(".kt")) {
            basename = basename.substring(0, basename.lastIndexOf('.'));
        }
        return Lookup.error(
                "CLASS_NOT_FOUND", "Class not found",
                IndexReader.searchClasses(jdbi, basename, 5),
                IndexReader.findFileCandidates(jdbi, target, 5));
    }

    static ObjectNode errorResponse(ObjectMapper json, Lookup lookup, String target) {
        ObjectNode root = json.createObjectNode();
        ToolResponseSupport.appendError(root, lookup.errorCode(), lookup.error());
        root.put("target", target);
        if ("Ambiguous class context".equals(lookup.error())) {
            root.put("reason", "The same FQCN exists in more than one module/source set; "
                    + "use its project path to select a concrete class");
        }
        root.set("accepted_target_types", json.valueToTree(
                List.of("symbol_id", "fqcn", "short_class_name", "project_path",
                        "repository_path")));
        ArrayNode candidates = root.putArray("candidates");
        for (ClassRecord candidate : lookup.candidates()) {
            ObjectNode node = candidates.addObject();
            node.put("class", candidate.className());
            node.put("file", candidate.sourceFile());
            node.put("origin", candidate.origin());
            node.put("lifecycle", candidate.lifecycle());
            if (candidate.module() != null) node.put("module", candidate.module());
            if (candidate.sourceSet() != null) node.put("source_set", candidate.sourceSet());
        }
        for (FileRecord candidate : lookup.fileCandidates()) {
            ObjectNode node = candidates.addObject();
            node.put("file", candidate.repositoryPath());
            node.put("origin", candidate.origin());
            node.put("lifecycle", candidate.lifecycle());
            if (candidate.module() != null) node.put("module", candidate.module());
            if (candidate.sourceSet() != null) node.put("source_set", candidate.sourceSet());
            node.put("reason", "matching Java source basename");
        }
        if ("AMBIGUOUS_CLASS".equals(lookup.errorCode())) {
            ToolResponseSupport.appendRetryWith(root, "target",
                    "Use one candidate's project path or symbol_id");
        }
        return root;
    }
}
