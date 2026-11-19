package org.treblereel.mcp.mcp.semantic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a field declaration in Java source text when compiler coordinates are unavailable. Only a
 * single declarator of that name and type directly in the owner's class body counts; locals,
 * annotation arguments, other classes' bodies and anything ambiguous give nothing.
 */
public final class JavaFieldSourceDeclaration {

    public record Found(int line, int endLine, String text) {
    }

    private static final Pattern TYPE_DECL = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern ANNOTATION = Pattern.compile("@\\s*[\\w.$]+(?:\\s*\\([^()]*\\))?");
    private static final Pattern MODIFIERS = Pattern.compile("\\b(?:public|protected|private|static|final|transient|volatile)\\b");

    private JavaFieldSourceDeclaration() {
    }

    public static Found find(String source, String owner, String name, String descriptor) {
        String simple = owner.substring(owner.lastIndexOf('.') + 1);
        List<String> path = List.of(simple.split("\\$"));
        String type = typeName(descriptor);
        if (type == null || path.stream().anyMatch(p -> p.isEmpty() || Character.isDigit(p.charAt(0)))) return null;
        String code = blank(source);
        Deque<String> stack = new ArrayDeque<>();
        int start = 0;
        Found found = null;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                Matcher decl = TYPE_DECL.matcher(code.substring(start, i));
                String body = null;
                while (decl.find()) body = decl.group(1);
                stack.push(body == null ? "" : body);
                start = i + 1;
            } else if (c == '}') {
                if (stack.isEmpty()) return null;
                stack.pop();
                start = i + 1;
            } else if (c == ';') {
                if (matches(stack, path)) {
                    Found candidate = statement(source, code, start, i, name, type);
                    if (candidate != null) {
                        if (found != null) return null;
                        found = candidate;
                    }
                }
                start = i + 1;
            }
        }
        return found;
    }

    private static boolean matches(Deque<String> stack, List<String> path) {
        if (stack.size() != path.size()) return false;
        List<String> outward = new ArrayList<>(stack);
        for (int i = 0; i < path.size(); i++) if (!path.get(i).equals(outward.get(path.size() - 1 - i))) return false;
        return true;
    }

    private static Found statement(String source, String code, int from, int to, String name, String type) {
        String text = code.substring(from, to);
        String head = ANNOTATION.matcher(text).replaceAll(m -> " ".repeat(m.group().length()));
        if (head.contains("(") || head.contains("@")) return null;
        Matcher declarator = Pattern.compile("(?:^|[\\s,>\\]])" + Pattern.quote(name) + "\\s*(?:=|,|$)").matcher(withoutInitializers(head));
        if (!declarator.find()) return null;
        String prefix = MODIFIERS.matcher(withoutInitializers(head)).replaceAll(" ").trim();
        String declared = prefix.split("\\s+(?=[A-Za-z_$][\\w$]*\\s*(?:=|,|$))", 2)[0].replaceAll("<.*>", "").replaceAll("\\s+", "");
        String simpleType = declared.substring(declared.lastIndexOf('.') + 1);
        if (!simpleType.equals(type)) return null;
        int first = from;
        while (first < to && Character.isWhitespace(code.charAt(first))) first++;
        return new Found(line(source, first), line(source, to), source.substring(first, to + 1));
    }

    /** Keeps declarator names, drops initializer expressions so they cannot pose as declarators. */
    private static String withoutInitializers(String head) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean value = false;
        for (char c : head.toCharArray()) {
            if (c == '(' || c == '[' || c == '<' && value) depth++;
            if (c == ')' || c == ']' || c == '>' && value && depth > 0) depth--;
            if (c == '=' && depth == 0) { value = true; out.append('='); continue; }
            if (c == ',' && depth == 0) value = false;
            out.append(value ? ' ' : c);
        }
        return out.toString();
    }

    private static String typeName(String descriptor) {
        int dims = 0;
        while (dims < descriptor.length() && descriptor.charAt(dims) == '[') dims++;
        String base = descriptor.substring(dims);
        String simple = switch (base) {
            case "Z" -> "boolean"; case "B" -> "byte"; case "C" -> "char"; case "S" -> "short";
            case "I" -> "int"; case "J" -> "long"; case "F" -> "float"; case "D" -> "double";
            default -> base.startsWith("L") && base.endsWith(";")
                    ? base.substring(Math.max(base.lastIndexOf('/'), base.lastIndexOf('$')) + 1, base.length() - 1) : null;
        };
        return simple == null ? null : simple + "[]".repeat(dims);
    }

    /** Comments, strings and char literals become spaces; newlines and offsets stay. */
    static String blank(String source) {
        char[] out = source.toCharArray();
        int i = 0;
        while (i < out.length) {
            if (source.startsWith("//", i)) {
                while (i < out.length && out[i] != '\n') out[i++] = ' ';
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? out.length : end + 2;
                for (; i < end; i++) if (out[i] != '\n') out[i] = ' ';
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? out.length : end + 3;
                for (; i < end; i++) if (out[i] != '\n') out[i] = ' ';
            } else if (out[i] == '"' || out[i] == '\'') {
                char quote = out[i];
                out[i++] = ' ';
                while (i < out.length && source.charAt(i) != quote && source.charAt(i) != '\n') {
                    if (source.charAt(i) == '\\' && i + 1 < out.length) out[i++] = ' ';
                    out[i++] = ' ';
                }
                if (i < out.length && source.charAt(i) == quote) out[i++] = ' ';
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int line(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) if (source.charAt(i) == '\n') line++;
        return line;
    }
}
