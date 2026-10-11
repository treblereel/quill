package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassRecord;

final class KotlinSourceLocations {
    private static final ObjectMapper JSON = new ObjectMapper();
    static void append(Jdbi db, ObjectNode node, int owner, List<Integer> lines) {
        var debug = IndexReader.findKotlinSourceDebug(db, owner);
        if (debug.isEmpty()) return;
        var cls = IndexReader.findClassById(db, owner);
        if (cls.isEmpty()) return;
        if (debug.get().malformedSmap()) node.put("source_mapping_incomplete", true);
        var locations = node.putArray("source_locations");
        var seen = new LinkedHashSet<String>();
        for (int line : lines) for (var mapping : debug.get().mappings()) {
            var sourceLine = mapping.sourceLine(line);
            if (sourceLine.isEmpty()) continue;
            String key = mapping.stratum() + "|" + mapping.path() + "|" + line + "|" + sourceLine.get();
            if (!seen.add(key)) continue;
            if (locations.size() >= 10000) { node.put("source_mapping_incomplete", true); break; }
            var item = locations.addObject();
            item.put("stratum", mapping.stratum()); item.put("generated_line", line);
            item.put("source_line", sourceLine.get()); item.put("source_name", mapping.file());
            item.put("source_path_hint", mapping.path());
            String path = sourcePath(db, cls.get(), mapping.path().replace('/', '.'), mapping.file());
            item.put("mapped", path != null);
            if (path != null) item.put("source_file", path);
        }
        var coroutine = debug.get().coroutine();
        if (coroutine != null) {
            var source = node.putObject("coroutine_source");
            source.put("class_name", coroutine.className()); source.put("method", coroutine.method());
            source.put("source_name", coroutine.file()); source.put("metadata_version", coroutine.version());
            source.put("supported", coroutine.supported());
            if (coroutine.supported()) {
                source.set("suspension_lines", JSON.valueToTree(coroutine.suspensionLines()));
                if (coroutine.version() == 2) source.set("next_lines", JSON.valueToTree(coroutine.nextLines()));
                String path = sourcePath(db, cls.get(), coroutine.className(), coroutine.file());
                if (path != null) source.put("source_file", path);
            }
            source.put("runtime_state_known", false);
        }
    }

    private static String sourcePath(Jdbi db, ClassRecord owner, String name, String file) {
        var visible = new LinkedHashSet<String>(); visible.add(owner.module());
        if (owner.module() != null) IndexReader.findModuleClasspath(db, owner.module()).forEach(value -> visible.add(value.visibleModule()));
        var paths = IndexReader.findClassesByName(db, name).stream()
                .filter(value -> visible.contains(value.module()) && value.sourceFile() != null
                        && (value.sourceFile().equals(file) || value.sourceFile().endsWith("/" + file))
                        && (!"main".equals(owner.sourceSet()) || !"test".equals(value.sourceSet())))
                .map(ClassRecord::sourceFile).distinct().toList();
        return paths.size() == 1 ? paths.getFirst() : null;
    }
}
