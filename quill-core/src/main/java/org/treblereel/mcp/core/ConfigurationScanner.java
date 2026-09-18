package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.IndexView;
import org.treblereel.mcp.model.ClassRecord;

/** Indexes configuration definitions and annotation-based consumers. */
public final class ConfigurationScanner {

    private static final String SPRING_VALUE =
            "org.springframework.beans.factory.annotation.Value";
    private static final String SPRING_PROPERTIES =
            "org.springframework.boot.context.properties.ConfigurationProperties";
    private static final String MICROPROFILE_PROPERTY =
            "org.eclipse.microprofile.config.inject.ConfigProperty";
    private static final String SMALLRYE_MAPPING = "io.smallrye.config.ConfigMapping";
    private static final Set<String> PERSISTENCE_CONTEXT = Set.of(
            "jakarta.persistence.PersistenceContext", "javax.persistence.PersistenceContext");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::[^}]*)?}");
    private static final Pattern PERSISTENCE_UNIT = Pattern.compile(
            "<persistence-unit\\b[^>]*\\bname\\s*=\\s*['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE);

    private ConfigurationScanner() {}

    public record Definition(
            String key, String kind, String file, int line, String module,
            String sourceSet) {}

    public record Usage(
            String key, String kind, int classId, String className, String member,
            Integer parameterIndex, String annotation, String source, String module,
            String sourceSet) {}

    public record Result(List<Definition> definitions, List<Usage> usages) {}

    public static Result scan(Path projectRoot, List<Path> moduleDirectories,
            IndexView index, Map<String, Integer> classNameToId, List<ClassRecord> classes) {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<Definition> definitions = scanDefinitions(root, moduleDirectories);
        Map<String, ClassRecord> classesByName = classes.stream().collect(
                java.util.stream.Collectors.toMap(ClassRecord::className, value -> value,
                        (left, right) -> left));
        List<Usage> usages = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (ClassInfo classInfo : index.getKnownClasses().stream()
                .sorted(Comparator.comparing(value -> value.name().toString())).toList()) {
            Integer classId = classNameToId.get(classInfo.name().toString());
            if (classId == null) continue;
            ClassRecord cls = classesByName.get(classInfo.name().toString());
            for (AnnotationInstance annotation : classInfo.annotations()) {
                addUsages(annotation, classId, classInfo.name().toString(), cls, usages, unique);
            }
        }
        usages.sort(Comparator.comparing(Usage::key)
                .thenComparing(Usage::className)
                .thenComparing(value -> value.member() == null ? "" : value.member())
                .thenComparing(value -> value.parameterIndex() == null
                        ? -1 : value.parameterIndex()));
        return new Result(List.copyOf(definitions), List.copyOf(usages));
    }

    private static List<Definition> scanDefinitions(Path root, List<Path> modules) {
        List<Definition> result = new ArrayList<>();
        Set<Path> resources = new LinkedHashSet<>();
        for (Path module : modules) {
            resources.add(module.resolve("src/main/resources"));
            resources.add(module.resolve("src/test/resources"));
        }
        for (Path directory : resources) {
            if (!Files.isDirectory(directory)) continue;
            try (Stream<Path> files = Files.walk(directory)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String name = file.getFileName().toString().toLowerCase();
                    SourceContext context = context(root, modules, file);
                    if (name.endsWith(".properties")) {
                        parseProperties(root, file, context, result);
                    } else if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                        parseYaml(root, file, context, result);
                    } else if (name.equals("persistence.xml")) {
                        parsePersistence(root, file, context, result);
                    }
                }
            } catch (IOException error) {
                throw new RuntimeException("Failed to inspect configuration under " + directory,
                        error);
            }
        }
        result.sort(Comparator.comparing(Definition::key)
                .thenComparing(Definition::file).thenComparingInt(Definition::line));
        return result;
    }

    private static void parseProperties(Path root, Path file, SourceContext context,
            List<Definition> target) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int separator = propertySeparator(line);
            if (separator <= 0) continue;
            String key = line.substring(0, separator).strip();
            if (!key.isEmpty()) target.add(new Definition(key, "property", relative(root, file),
                    i + 1, context.module(), context.sourceSet()));
        }
    }

    private static int propertySeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if ((value == '=' || value == ':' || Character.isWhitespace(value))
                    && !isEscaped(line, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isEscaped(String value, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && value.charAt(i) == '\\'; i--) backslashes++;
        return backslashes % 2 != 0;
    }

    private static void parseYaml(Path root, Path file, SourceContext context,
            List<Definition> target) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Deque<YamlKey> parents = new ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String stripped = raw.strip();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.equals("---")) {
                if (stripped.equals("---")) parents.clear();
                continue;
            }
            int colon = stripped.indexOf(':');
            if (colon <= 0 || stripped.startsWith("-")) continue;
            int indent = leadingSpaces(raw);
            while (!parents.isEmpty() && parents.peekLast().indent() >= indent) {
                parents.removeLast();
            }
            String local = unquote(stripped.substring(0, colon).strip());
            if (local.isEmpty()) continue;
            String key = Stream.concat(parents.stream().map(YamlKey::key), Stream.of(local))
                    .reduce((left, right) -> left + "." + right).orElse(local);
            String value = stripYamlComment(stripped.substring(colon + 1).strip());
            if (value.isEmpty()) {
                parents.addLast(new YamlKey(indent, local));
            } else {
                target.add(new Definition(key, "yaml_property", relative(root, file), i + 1,
                        context.module(), context.sourceSet()));
            }
        }
    }

    private static void parsePersistence(Path root, Path file, SourceContext context,
            List<Definition> target) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = PERSISTENCE_UNIT.matcher(lines.get(i));
            while (matcher.find()) {
                target.add(new Definition(matcher.group(1), "persistence_unit",
                        relative(root, file), i + 1, context.module(), context.sourceSet()));
            }
        }
    }

    private static void addUsages(AnnotationInstance annotation, int classId, String className,
            ClassRecord cls, List<Usage> target, Set<String> unique) {
        if (annotation.target() == null) return;
        String annotationName = annotation.name().toString();
        List<KeyKind> keys;
        if (annotationName.equals(SPRING_VALUE)) {
            keys = strings(annotation.value()).stream()
                    .flatMap(value -> placeholders(value).stream())
                    .map(value -> new KeyKind(value, "config_key")).toList();
        } else if (annotationName.equals(MICROPROFILE_PROPERTY)) {
            String name = string(annotation.value("name"));
            if (name == null || name.isBlank()) name = inferredName(annotation.target());
            keys = name == null ? List.of() : List.of(new KeyKind(name, "config_key"));
        } else if (annotationName.equals(SPRING_PROPERTIES)
                || annotationName.equals(SMALLRYE_MAPPING)) {
            String prefix = string(annotation.value("prefix"));
            if (prefix == null) prefix = string(annotation.value());
            keys = prefix == null || prefix.isBlank() ? List.of()
                    : List.of(new KeyKind(prefix, "config_prefix"));
        } else if (PERSISTENCE_CONTEXT.contains(annotationName)) {
            String unit = string(annotation.value("unitName"));
            keys = unit == null || unit.isBlank() ? List.of()
                    : List.of(new KeyKind(unit, "persistence_unit"));
        } else {
            return;
        }
        TargetContext context = targetContext(annotation.target());
        for (KeyKind key : keys) {
            String identity = classId + "\n" + context.member() + "\n"
                    + context.parameterIndex() + "\n" + annotationName + "\n"
                    + key.kind() + "\n" + key.key();
            if (!unique.add(identity)) continue;
            target.add(new Usage(key.key(), key.kind(), classId, className, context.member(),
                    context.parameterIndex(), annotationName,
                    cls == null ? null : cls.sourceFile(), cls == null ? null : cls.module(),
                    cls == null ? null : cls.sourceSet()));
        }
    }

    private static TargetContext targetContext(AnnotationTarget target) {
        return switch (target.kind()) {
            case FIELD -> new TargetContext(target.asField().name(), null);
            case METHOD -> new TargetContext(target.asMethod().name(), null);
            case METHOD_PARAMETER -> new TargetContext(
                    target.asMethodParameter().method().name(),
                    (int) target.asMethodParameter().position());
            default -> new TargetContext(null, null);
        };
    }

    private static String inferredName(AnnotationTarget target) {
        return switch (target.kind()) {
            case FIELD -> target.asField().name();
            case METHOD_PARAMETER -> target.asMethodParameter().nameOrDefault();
            default -> null;
        };
    }

    private static List<String> strings(AnnotationValue value) {
        if (value == null) return List.of();
        try {
            if (value.kind() == AnnotationValue.Kind.ARRAY) {
                return Arrays.asList(value.asStringArray());
            }
            return List.of(value.asString());
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    private static String string(AnnotationValue value) {
        List<String> values = strings(value);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static List<String> placeholders(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) result.add(matcher.group(1).strip());
        return result;
    }

    private static SourceContext context(Path root, List<Path> modules, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path owner = modules.stream().map(path -> path.toAbsolutePath().normalize())
                .filter(absolute::startsWith).max(Comparator.comparingInt(Path::getNameCount))
                .orElse(root);
        String module = owner.equals(root) ? "." : relative(root, owner);
        String relative = relative(owner, absolute);
        String sourceSet = relative.startsWith("src/test/") ? "test" : "main";
        return new SourceContext(module, sourceSet);
    }

    private static int leadingSpaces(String value) {
        int result = 0;
        while (result < value.length() && value.charAt(result) == ' ') result++;
        return result;
    }

    private static String stripYamlComment(String value) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if ((current == '\'' || current == '"') && (i == 0 || value.charAt(i - 1) != '\\')) {
                if (!quoted) {
                    quoted = true;
                    quote = current;
                } else if (quote == current) {
                    quoted = false;
                }
            }
            if (current == '#' && !quoted) return value.substring(0, i).strip();
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String relative(Path root, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return (absolute.startsWith(root) ? root.relativize(absolute) : absolute)
                .toString().replace('\\', '/');
    }

    private record KeyKind(String key, String kind) {}
    private record TargetContext(String member, Integer parameterIndex) {}
    private record SourceContext(String module, String sourceSet) {}
    private record YamlKey(int indent, String key) {}
}
