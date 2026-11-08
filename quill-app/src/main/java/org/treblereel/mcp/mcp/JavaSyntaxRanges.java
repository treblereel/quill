package org.treblereel.mcp.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Method declaration ranges read from Java syntax alone, for projects built without Quill's compiler
 * capture. Comments, strings, text blocks and char literals are skipped, so braces inside them never
 * move a range. Callers pick among the candidates with bytecode lines; this class never guesses one.
 */
final class JavaSyntaxRanges {

    /** Offsets into the source text: {@code start} inclusive, {@code end} exclusive. */
    record Range(int start, int end, int startLine, int endLine) {
        boolean contains(int line) { return line >= startLine && line <= endLine; }
    }

    private record Token(String text, int start, int end) {}

    private static final Set<String> NOT_A_RETURN_TYPE = Set.of("return", "throw", "else", "case", "new",
            "yield", "assert", "do", "default");

    private JavaSyntaxRanges() {}

    /** Declarations of {@code name} with {@code arity} parameters; a constructor is named after its class. */
    static List<Range> declarations(String text, String name, int arity) {
        List<Token> tokens = tokens(text);
        List<Range> found = new ArrayList<>();
        for (int i = 1; i + 1 < tokens.size(); i++) {
            if (!tokens.get(i).text().equals(name) || !tokens.get(i + 1).text().equals("(")) continue;
            String before = tokens.get(i - 1).text();
            if (before.equals(".") || before.equals("new") || before.equals("::")) continue;
            int close = matching(tokens, i + 1, "(", ")");
            if (close < 0 || parameters(tokens, i + 1, close) != arity) continue;
            int next = close + 1;
            if (next < tokens.size() && tokens.get(next).text().equals("throws")) {
                while (next < tokens.size() && !tokens.get(next).text().equals("{") && !tokens.get(next).text().equals(";")) next++;
            } else if (next < tokens.size() && tokens.get(next).text().equals("default")) {
                while (next < tokens.size() && !tokens.get(next).text().equals(";")) next++;
            }
            if (next >= tokens.size()) continue;
            int last;
            if (tokens.get(next).text().equals("{")) {
                last = matching(tokens, next, "{", "}");
            } else if (tokens.get(next).text().equals(";")) {
                // A call statement ends the same way; a declaration has a return type before its name.
                boolean typed = before.equals(">") || before.equals("]")
                        || isIdentifier(before) && !NOT_A_RETURN_TYPE.contains(before);
                last = typed ? next : -1;
            } else {
                last = -1;
            }
            if (last < 0) continue;
            int first = headerStart(tokens, i);
            int start = tokens.get(first).start(), end = tokens.get(last).end();
            found.add(new Range(start, end, line(text, start), line(text, end - 1)));
        }
        return found;
    }

    /** Back over modifiers, annotations and the return type to the end of the previous member. */
    private static int headerStart(List<Token> tokens, int name) {
        int i = name - 1;
        while (i >= 0) {
            String text = tokens.get(i).text();
            if (text.equals(";") || text.equals("{") || text.equals("}")) break;
            if (text.equals(")")) {
                int depth = 0;
                for (; i >= 0; i--) {
                    String t = tokens.get(i).text();
                    if (t.equals(")")) depth++;
                    else if (t.equals("(") && --depth == 0) break;
                }
            }
            i--;
        }
        return i + 1;
    }

    private static int parameters(List<Token> tokens, int open, int close) {
        if (close == open + 1) return 0;
        int count = 1, parens = 0, angles = 0;
        for (int i = open + 1; i < close; i++) {
            switch (tokens.get(i).text()) {
                case "(" -> parens++;
                case ")" -> parens--;
                case "<" -> angles++;
                case ">" -> angles--;
                case "," -> { if (parens == 0 && angles == 0) count++; }
                default -> { }
            }
        }
        return count;
    }

    private static int matching(List<Token> tokens, int open, String opening, String closing) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            String text = tokens.get(i).text();
            if (text.equals(opening)) depth++;
            else if (text.equals(closing) && --depth == 0) return i;
        }
        return -1;
    }

    private static boolean isIdentifier(String text) {
        return !text.isEmpty() && Character.isJavaIdentifierStart(text.charAt(0));
    }

    static int line(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private static List<Token> tokens(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
                continue;
            }
            if (text.startsWith("\"\"\"", i)) {
                int close = i + 3;
                while (close < n && !(text.startsWith("\"\"\"", close) && text.charAt(close - 1) != '\\')) close++;
                tokens.add(new Token("\"\"\"", i, Math.min(n, close + 3)));
                i = Math.min(n, close + 3);
                continue;
            }
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && text.charAt(j) != c && text.charAt(j) != '\n') j += text.charAt(j) == '\\' ? 2 : 1;
                tokens.add(new Token(String.valueOf(c), i, Math.min(n, j + 1)));
                i = Math.min(n, j + 1);
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int j = i + 1;
                while (j < n && Character.isJavaIdentifierPart(text.charAt(j))) j++;
                tokens.add(new Token(text.substring(i, j), i, j));
                i = j;
                continue;
            }
            if (c == ':' && i + 1 < n && text.charAt(i + 1) == ':') {
                tokens.add(new Token("::", i, i + 2));
                i += 2;
                continue;
            }
            tokens.add(new Token(String.valueOf(c), i, i + 1));
            i++;
        }
        return tokens;
    }
}
