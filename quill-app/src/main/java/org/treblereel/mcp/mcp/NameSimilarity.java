package org.treblereel.mcp.mcp;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Orders declared names by how close they are to a name an agent guessed, for a not-found answer. */
final class NameSimilarity {

    private NameSimilarity() {
    }

    /** The closest {@code limit} distinct names; names sharing nothing with the guess come last, alphabetically. */
    static List<String> closest(String guess, Collection<String> names, int limit) {
        String query = guess.toLowerCase(Locale.ROOT);
        Set<String> words = words(guess);
        return new TreeSet<>(names).stream()
                .sorted(Comparator.comparingInt((String name) -> -score(query, words, name)))
                .limit(limit).toList();
    }

    private static int score(String query, Set<String> words, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int score = 0;
        if (lower.contains(query) || query.contains(lower)) score += 100;
        for (String word : words(name)) if (words.contains(word)) score += 20;
        int prefix = 0;
        while (prefix < Math.min(lower.length(), query.length()) && lower.charAt(prefix) == query.charAt(prefix)) prefix++;
        return score + prefix;
    }

    /** camelCase and snake_case words, lower-cased. */
    private static Set<String> words(String name) {
        Set<String> words = new TreeSet<>();
        for (String word : name.split("(?<=[a-z0-9])(?=[A-Z])|[_$.\\-]+"))
            if (word.length() > 1) words.add(word.toLowerCase(Locale.ROOT));
        return words;
    }
}
