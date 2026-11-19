package org.treblereel.mcp.mcp.change;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a caller does with a method's returned value, read from its source: the calling statement and,
 * when the result is assigned, the calls made on it in the rest of the block. A signature change that
 * alters the return type breaks exactly these uses.
 */
final class CallerResultUse {

    private static final int STATEMENT_LINES = 3, FOLLOW_LINES = 30, MAX_USES = 8;
    private static final Pattern UNWRAP = Pattern.compile(
            "(get|orElse\\w*|orElseThrow|join|await\\w*|getValue|value|getFirst|iterator)");

    private CallerResultUse() {
    }

    static String describe(List<String> lines, int line, String method) {
        return describe(lines, line, method, null);
    }

    /**
     * {@code line N: statement[ var is Type;][ then var.call() (line M), ...]}, or null when the line is
     * out of range. The returned type is named once the result is assigned: the uses that follow are
     * calls on that type, the part of a caller a return type change breaks.
     */
    static String describe(List<String> lines, int line, String method, String returnType) {
        if (line < 1 || line > lines.size()) return null;
        StringBuilder statement = new StringBuilder();
        int last = line;
        for (int i = line; i <= Math.min(lines.size(), line + STATEMENT_LINES - 1); i++) {
            statement.append(statement.isEmpty() ? "" : " ").append(lines.get(i - 1).strip());
            last = i;
            if (lines.get(i - 1).contains(";")) break;
        }
        String text = "line " + line + ": " + statement;
        Matcher assigned = Pattern.compile("(\\w+)\\s*=(?!=)[^=;]*\\b" + Pattern.quote(method) + "\\s*\\(")
                .matcher(statement);
        if (!assigned.find()) return text;
        List<String> tracked = new ArrayList<>(List.of(assigned.group(1)));
        // A call repeated on the value lists its lines once: "k8s.verb() (lines 149, 163)".
        Map<String, List<Integer>> uses = new LinkedHashMap<>();
        Map<String, String> aliases = new HashMap<>();
        int depth = 0;
        for (int i = last + 1; i <= Math.min(lines.size(), last + FOLLOW_LINES) && uses.size() < MAX_USES; i++) {
            String code = lines.get(i - 1);
            for (String name : List.copyOf(tracked)) {
                Matcher call = Pattern.compile("\\b" + Pattern.quote(name) + "\\.(\\w+)\\s*\\(").matcher(code);
                while (call.find() && uses.size() < MAX_USES) {
                    String use = name + "." + call.group(1) + "()";
                    Matcher alias = Pattern.compile("(\\w+)\\s*=(?!=)\\s*" + Pattern.quote(name) + "\\."
                            + UNWRAP.pattern() + "\\s*\\(").matcher(code);
                    if (alias.find() && alias.group(2).equals(call.group(1)) && tracked.size() < 3
                            && !tracked.contains(alias.group(1))) {
                        aliases.put(use, alias.group(1));
                        tracked.add(alias.group(1));
                    }
                    List<Integer> at = uses.computeIfAbsent(use, ignored -> new ArrayList<>());
                    if (!at.contains(i)) at.add(i);
                }
            }
            for (char c : code.toCharArray()) {
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            if (depth < 0) break;
        }
        if (returnType != null && !returnType.isBlank())
            text += " " + tracked.get(0) + " is " + returnType + ";";
        if (uses.isEmpty()) return text;
        List<String> listed = new ArrayList<>();
        uses.forEach((use, at) -> listed.add(use + " (line" + (at.size() > 1 ? "s " : " ")
                + String.join(", ", at.stream().map(String::valueOf).toList()) + ")"
                + (aliases.containsKey(use) ? " as " + aliases.get(use) : "")));
        return text + " then " + String.join(", ", listed);
    }

    /** {@code resolve(java.lang.String):java.util.Optional<p.Mapper$Verb>} to {@code Optional<Verb>}. */
    static String returnType(String signature) {
        if (signature == null) return null;
        int close = signature.lastIndexOf("):");
        if (close < 0) return null;
        String type = signature.substring(close + 2).strip();
        if (type.isEmpty() || type.equals("void")) return null;
        return type.replaceAll("(?:[\\p{L}_$][\\p{L}\\p{N}_]*[.$])+([\\p{L}_][\\p{L}\\p{N}_]*)", "$1");
    }
}
