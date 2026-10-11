package org.treblereel.mcp.core;

/** Small lexical helpers; deliberately does not attempt Kotlin type resolution. */
public final class KotlinSourceText {
    private KotlinSourceText() {}

    /** Masks comments and literals while preserving offsets and line breaks. */
    public static String code(String source) {
        return code(source, false);
    }

    /** Like code(), but retains one marker per literal for argument counting. */
    public static String codeWithLiteralMarkers(String source) {
        return code(source, true);
    }

    private static String code(String source, boolean literalMarkers) {
        StringBuilder result = new StringBuilder(source);
        int comments = 0;
        char quote = 0;
        boolean raw = false;
        boolean backtick = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (comments > 0) {
                if (c == '/' && next == '*') { comments++; mask(result, i++); }
                else if (c == '*' && next == '/') { comments--; mask(result, i++); }
                mask(result, i);
            } else if (quote != 0) {
                if (raw && source.startsWith("\"\"\"", i)) {
                    mask(result, i); mask(result, ++i); mask(result, ++i);
                    quote = 0; raw = false;
                } else if (!raw && c == '\\') {
                    mask(result, i);
                    if (i + 1 < source.length()) mask(result, ++i);
                } else {
                    mask(result, i);
                    if (!raw && c == quote) quote = 0;
                }
            } else if (c == '`') {
                backtick = !backtick;
            } else if (!backtick && c == '/' && next == '*') {
                comments = 1; mask(result, i); mask(result, ++i);
            } else if (!backtick && c == '/' && next == '/') {
                while (i < source.length() && source.charAt(i) != '\n') mask(result, i++);
            } else if (!backtick && (c == '"' || c == '\'')) {
                quote = c;
                raw = c == '"' && source.startsWith("\"\"\"", i);
                if (literalMarkers) result.setCharAt(i, '?');
                else mask(result, i);
                if (raw) { mask(result, ++i); mask(result, ++i); }
            }
        }
        return result.toString();
    }

    private static void mask(StringBuilder text, int index) {
        if (text.charAt(index) != '\n' && text.charAt(index) != '\r') text.setCharAt(index, ' ');
    }

    public static String packageName(String source) {
        var matcher = java.util.regex.Pattern.compile(
                "(?m)^\\h*package\\h+((?:`[^`\\r\\n]+`|[\\p{L}_$][\\p{L}\\p{N}_$]*)(?:\\h*\\.\\h*(?:`[^`\\r\\n]+`|[\\p{L}_$][\\p{L}\\p{N}_$]*))*)")
                .matcher(code(source));
        return matcher.find() ? matcher.group(1).replace("`", "").replaceAll("\\h", "") : "";
    }
}
