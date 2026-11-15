package org.treblereel.mcp.mcp.query;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.model.ClassRecord;

/** Conservative erasure matching: incomplete hierarchies and generic matches stay candidates. */
final class ObserverEventFilter {
    enum Match { COMPATIBLE, UNKNOWN, INCOMPATIBLE }
    private final Set<String> types = new LinkedHashSet<>();
    private final Set<String> missing = new LinkedHashSet<>();

    ObserverEventFilter(String event, List<ClassRecord> classes) {
        Map<String, ClassRecord> byName = new HashMap<>();
        classes.forEach(cls -> byName.put(canonical(cls.className()), cls));
        var pending = new ArrayDeque<String>();
        pending.add(raw(event));
        while (!pending.isEmpty()) {
            String type = pending.removeFirst();
            if (!types.add(type) || type.equals("java.lang.Object")) continue;
            ClassRecord cls = byName.get(type);
            if (cls == null) { missing.add(type); continue; }
            if (cls.superclass() != null) pending.add(raw(cls.superclass()));
            cls.interfaces().forEach(parent -> pending.add(raw(parent)));
        }
        types.add("java.lang.Object");
    }

    Match match(String observed) {
        if (observed == null || observed.isBlank()) return Match.UNKNOWN;
        String type = raw(observed);
        if (type.equals("java.lang.Object")) return Match.COMPATIBLE;
        if (types.contains(type)) return observed.contains("<") || observed.contains("?")
                ? Match.UNKNOWN : Match.COMPATIBLE;
        // A type variable or a missing parent could still accept this event.
        return !missing.isEmpty() || !type.contains(".") ? Match.UNKNOWN : Match.INCOMPATIBLE;
    }

    List<String> missingTypes() { return List.copyOf(missing); }
    private static String raw(String type) {
        int generic = type.indexOf('<');
        return canonical((generic < 0 ? type : type.substring(0, generic)).strip());
    }
    private static String canonical(String type) { return type.replace('$', '.'); }
}
