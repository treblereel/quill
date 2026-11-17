package org.treblereel.mcp.mcp.prefetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.diagnostics.QuillLog;
import org.treblereel.mcp.mcp.project.ProjectDependencyQueries;
import org.treblereel.mcp.mcp.support.BeanTerms;

/**
 * Where a method hands work to another thread (Mutiny runSubscriptionOn/emitOn, Reactor
 * subscribeOn/publishOn, executors, CompletableFuture *Async, Vert.x executeBlocking) and which
 * beans the code it hands off reaches: the lambdas it creates and what they call, three calls deep.
 * V55 CQ-025: asked whether an async path may use contextual beans, Codex read each class for its
 * scope.
 */
final class ThreadHops {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final int DEPTH = 3;
    private static final int LIMIT = 12;

    private ThreadHops() {}

    static boolean switchesThread(String owner, String method) {
        if (owner.startsWith("io.smallrye.mutiny."))
            return method.equals("runSubscriptionOn") || method.equals("emitOn");
        if (owner.startsWith("reactor.core.publisher."))
            return method.equals("subscribeOn") || method.equals("publishOn");
        if (owner.equals("java.util.concurrent.CompletableFuture")) return method.endsWith("Async");
        if (owner.startsWith("java.util.concurrent.")
                && (owner.endsWith("Executor")
                        || owner.endsWith("ExecutorService")
                        || owner.endsWith("ScheduledExecutorService")))
            return Set.of(
                            "execute",
                            "submit",
                            "schedule",
                            "invokeAll",
                            "invokeAny",
                            "scheduleAtFixedRate",
                            "scheduleWithFixedDelay")
                    .contains(method);
        if (owner.startsWith("org.eclipse.microprofile.context.ManagedExecutor"))
            return method.equals("execute") || method.equals("submit") || method.endsWith("Async");
        if (owner.contains(".vertx.") && (owner.endsWith(".Vertx") || owner.endsWith(".Context")))
            return method.startsWith("executeBlocking") || method.equals("runOnContext");
        return owner.equals("java.lang.Thread") && method.equals("start");
    }

    static ObjectNode of(ReadQueryContext db, int classId, String method, String descriptor)
            throws Exception {
        return of(db, null, classId, method, descriptor);
    }

    /**
     * With {@code root}, also the values each handed-off lambda captures and whether context
     * propagation is on the classpath.
     */
    static ObjectNode of(
            ReadQueryContext db, Path root, int classId, String method, String descriptor)
            throws Exception {
        String external =
                db.usages.findCallerExternalCalls(classId, method, descriptor).orElse("[]");
        List<String> switches = new ArrayList<>();
        String executor = null;
        for (JsonNode call : JSON.readTree(external)) {
            // ordinal|line|owner|method|descriptor|phase
            String[] parts = call.asText().split("\\|", -1);
            if (parts.length < 4) continue;
            if (parts[2].equals("io.smallrye.mutiny.infrastructure.Infrastructure")
                    && parts[3].equals("getDefaultExecutor"))
                executor = "Infrastructure#getDefaultExecutor";
            if (switchesThread(parts[2], parts[3]))
                switches.add(
                        "line "
                                + parts[1]
                                + " "
                                + parts[2]
                                + "#"
                                + parts[3]
                                + (executor == null ? "" : "(" + executor + ")"));
        }
        if (switches.isEmpty()) return null;
        ObjectNode fact = JSON.createObjectNode();
        fact.set("switches", JSON.valueToTree(switches));
        Set<String> reached = new LinkedHashSet<>();
        Set<String> requestScoped = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        List<String> source = root == null ? List.of() : source(db, root, classId);
        Set<String> fields = source.isEmpty() ? Set.of() : fields(db, classId);
        List<String> captures = new ArrayList<>();
        for (var lambda : db.usages.findLambdaCalls(classId, method, descriptor)) {
            queue.add(
                    new Object[] {lambda.toClassId(), lambda.toMethod(), lambda.toDescriptor(), 1});
            if (source.isEmpty() || lambda.evidenceLines() == null) continue;
            for (JsonNode line : JSON.readTree(lambda.evidenceLines())) {
                String captured =
                        captured(
                                source,
                                line.asInt(),
                                String.valueOf(lambda.toDescriptor()),
                                fields);
                if (captured != null) captures.add(captured);
            }
        }
        if (!captures.isEmpty()) fact.set("captured", JSON.valueToTree(captures));
        while (!queue.isEmpty() && reached.size() < LIMIT) {
            Object[] current = queue.removeFirst();
            int id = (int) current[0];
            String name = String.valueOf(current[1]), desc = String.valueOf(current[2]);
            int depth = (int) current[3];
            if (!seen.add(id + "#" + name + desc)) continue;
            String shown = name.replaceFirst("^lambda\\$(.+)\\$\\d+$", "$1 (lambda)");
            for (var row : db.beans.findInjectedFieldReads(id, name, desc)) {
                String scope = row.scope();
                String bean =
                        row.resolved() == null ? String.valueOf(row.targetType()) : row.resolved();
                reached.add(
                        bean
                                + " "
                                + (scope == null
                                        ? "unresolved"
                                        : BeanTerms.scope((Object) row.framework(), scope))
                                + ": "
                                + row.fieldName()
                                + " in "
                                + shown);
                if ("@RequestScoped".equals(scope) || "@SessionScoped".equals(scope))
                    requestScoped.add(bean);
            }
            if (depth >= DEPTH) continue;
            for (var call : db.usages.findCallTargets(id, name, desc))
                queue.add(
                        new Object[] {
                            call.toClassId(),
                            call.toMethod(),
                            call.toDescriptor(),
                            String.valueOf(call.toMethod()).startsWith("lambda$")
                                    ? depth
                                    : depth + 1
                        });
        }
        ArrayNode beans = fact.putArray("beans_reached_after");
        // Application beans and their scopes answer the question; unresolved framework fields go
        // last.
        reached.stream().filter(line -> !line.contains(" unresolved: ")).forEach(beans::add);
        reached.stream().filter(line -> line.contains(" unresolved: ")).forEach(beans::add);
        fact.set("request_scoped_after", JSON.valueToTree(requestScoped));
        // Apollo AQ-025: a static ThreadLocal set on the request thread (UserIdentityContextHolder)
        // is empty after the
        // switch; agents grepped for it in every session.
        List<String> bound =
                new ArrayList<>(db.symbols.findStaticThreadLocalFields());
        List<Path> jarNames = root == null ? List.of() : jars(db, root);
        if (root != null) {
            String propagation = propagation(jarNames);
            if (propagation != null) fact.put("context_propagation", propagation);
        }
        if (jarNames.stream()
                .anyMatch(jar -> jar.getFileName().toString().contains("spring-security-core")))
            bound.add(
                    "org.springframework.security.core.context.SecurityContextHolder (thread-local strategy by default)");
        if (jarNames.stream()
                .anyMatch(jar -> jar.getFileName().toString().startsWith("spring-web-")))
            bound.add(
                    "org.springframework.web.context.request.RequestContextHolder (request attributes)");
        if (!bound.isEmpty()) {
            fact.set("thread_bound_state", JSON.valueToTree(bound));
            fact.put(
                    "thread_bound_state_basis",
                    "static ThreadLocal holders in application code, and framework holders on "
                            + "the classpath; what the calling thread set in them is not visible after the switch unless copied");
        }
        fact.put(
                "basis",
                "injected fields read by this method's lambdas and their callees, "
                        + DEPTH
                        + " deep; captured values read from the lambda's source and its synthetic method's parameters");
        return fact;
    }

    private static final Pattern LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern NAME =
            Pattern.compile("(?<![.\\w$])([a-z_][\\w$]*)\\b(?!\\s*\\()");
    private static final Pattern DECLARED =
            Pattern.compile(
                    "\\b(?:var|int|long|boolean|double|float|char|byte|short|[A-Z][\\w.$]*(?:<[^>]*>)?(?:\\[])?)\\s+([a-z_][\\w$]*)\\s*[=:;]");
    private static final Pattern LAMBDA_PARAMS =
            Pattern.compile("(?:\\(([^()]*)\\)|([a-z_][\\w$]*))\\s*->");
    private static final Set<String> KEYWORDS =
            Set.of(
                    "return",
                    "new",
                    "var",
                    "true",
                    "false",
                    "null",
                    "this",
                    "super",
                    "instanceof",
                    "final",
                    "if",
                    "else",
                    "for",
                    "while",
                    "do",
                    "try",
                    "catch",
                    "finally",
                    "throw",
                    "switch",
                    "case",
                    "default",
                    "break",
                    "continue",
                    "yield",
                    "int",
                    "long",
                    "boolean",
                    "double",
                    "float",
                    "char",
                    "byte",
                    "short",
                    "class");

    /**
     * "line N: name Type, ..." for the lambda written at {@code line}: names its body reads that it
     * neither declares nor takes as parameters, nor are fields; javac passes them first, so the
     * synthetic method's leading parameter types are theirs.
     */
    static String captured(
            List<String> lines, int line, String lambdaDescriptor, Set<String> fields) {
        if (line < 1 || line > lines.size()) return null;
        String text =
                LITERAL.matcher(
                                String.join(
                                        "\n",
                                        lines.subList(line - 1, Math.min(lines.size(), line + 19))))
                        .replaceAll("\"\"");
        int arrow = text.indexOf("->");
        if (arrow < 0 || text.substring(0, arrow).chars().filter(c -> c == '\n').count() > 3)
            return null;
        String before = text.substring(0, arrow).stripTrailing();
        Set<String> own = new LinkedHashSet<>();
        if (before.endsWith(")")) {
            int depth = 0, open = before.length() - 1;
            for (; open >= 0; open--) {
                char c = before.charAt(open);
                if (c == ')') depth++;
                else if (c == '(' && --depth == 0) break;
            }
            for (String parameter :
                    before.substring(Math.max(0, open + 1), before.length() - 1).split(",")) {
                Matcher last = Pattern.compile("([\\w$]+)\\s*$").matcher(parameter.strip());
                if (last.find()) own.add(last.group(1));
            }
        } else {
            Matcher last = Pattern.compile("([\\w$]+)$").matcher(before);
            if (last.find()) own.add(last.group(1));
        }
        int start = arrow + 2;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        int end = start, depth = 0;
        boolean block = start < text.length() && text.charAt(start) == '{';
        for (; end < text.length(); end++) {
            char c = text.charAt(end);
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') {
                if (--depth < 0 || (block && depth == 0)) break;
            } else if (!block && depth == 0 && (c == ',' || c == ';')) break;
        }
        String body = text.substring(start, Math.min(end + (block ? 1 : 0), text.length()));
        Set<String> local = new LinkedHashSet<>(own);
        Matcher declared = DECLARED.matcher(body);
        while (declared.find()) local.add(declared.group(1));
        Matcher nested = LAMBDA_PARAMS.matcher(body);
        while (nested.find()) {
            String names = nested.group(1) != null ? nested.group(1) : nested.group(2);
            for (String name : names.split(",")) {
                Matcher last = Pattern.compile("([\\w$]+)\\s*$").matcher(name.strip());
                if (last.find()) local.add(last.group(1));
            }
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher name = NAME.matcher(body);
        while (name.find()) {
            String candidate = name.group(1);
            if (!KEYWORDS.contains(candidate)
                    && !local.contains(candidate)
                    && !fields.contains(candidate)) names.add(candidate);
        }
        if (names.isEmpty()) return null;
        List<String> types = parameterTypes(lambdaDescriptor);
        List<String> shown = new ArrayList<>();
        int i = 0;
        boolean typed = types.size() - own.size() == names.size();
        for (String captured : names) shown.add(typed ? captured + " " + types.get(i++) : captured);
        return "line " + line + ": " + String.join(", ", shown);
    }

    private static List<String> parameterTypes(String descriptor) {
        List<String> types = new ArrayList<>();
        if (descriptor == null || !descriptor.startsWith("(")) return types;
        int i = 1;
        while (i < descriptor.length() && descriptor.charAt(i) != ')') {
            int dims = 0;
            while (descriptor.charAt(i) == '[') {
                dims++;
                i++;
            }
            String type;
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int semi = descriptor.indexOf(';', i);
                String internal = descriptor.substring(i + 1, semi);
                type = internal.substring(internal.lastIndexOf('/') + 1);
                i = semi + 1;
            } else {
                type =
                        switch (c) {
                            case 'Z' -> "boolean";
                            case 'B' -> "byte";
                            case 'C' -> "char";
                            case 'S' -> "short";
                            case 'I' -> "int";
                            case 'J' -> "long";
                            case 'F' -> "float";
                            case 'D' -> "double";
                            default -> String.valueOf(c);
                        };
                i++;
            }
            types.add(type + "[]".repeat(dims));
        }
        return types;
    }

    /**
     * Whether the CDI request context can follow the work to the executor thread, from the captured
     * classpath.
     */
    static String propagation(List<Path> jars) {
        if (jars.isEmpty()) return null;
        List<String> found = new ArrayList<>();
        Path core = null;
        for (Path jar : jars) {
            String file = jar.getFileName().toString();
            if (file.startsWith("quarkus-smallrye-context-propagation-")
                    || file.startsWith("mutiny-smallrye-context-propagation-"))
                found.add(coordinates(jar));
            else if (file.matches("smallrye-context-propagation-\\d.*")) core = jar;
        }
        if (found.isEmpty() && core != null) found.add(coordinates(core));
        if (found.isEmpty())
            return "no context-propagation artifact on the classpath (of "
                    + jars.size()
                    + " jars): request/session context is not carried to the other thread";
        String mechanism = mechanism(jars);
        return "on the classpath: "
                + String.join(", ", found)
                + "; with them Quarkus carries the CDI request context active when the pipeline is built to the executor thread"
                + (mechanism.isEmpty() ? "" : "; " + mechanism);
    }

    /** Services that decorate Mutiny callbacks, read from the captured jars. */
    private static final List<String> DECORATOR_SERVICES =
            List.of(
                    "META-INF/services/io.smallrye.mutiny.infrastructure.CallbackDecorator",
                    "META-INF/services/io.smallrye.mutiny.infrastructure.UniInterceptor");

    private static final String ARC_PROVIDER = "io.quarkus.arc.runtime.context.ArcContextProvider";

    /**
     * The registered decorator that wraps Uni callbacks and the provider that captures the CDI
     * context, as the jars declare them. Cryostat CQ-025: told only that the artifacts were present,
     * Codex opened the libraries to find both (7 of 11 calls).
     */
    private static String mechanism(List<Path> jars) {
        Set<String> decorators = new LinkedHashSet<>();
        boolean arcProvider = false;
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) continue;
            try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
                for (String service : DECORATOR_SERVICES) {
                    var entry = zip.getEntry(service);
                    if (entry == null) continue;
                    String text = new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    text.lines()
                            .map(line -> line.replaceFirst("#.*", "").strip())
                            .filter(line -> !line.isEmpty())
                            .forEach(decorators::add);
                }
                if (zip.getEntry(ARC_PROVIDER.replace('.', '/') + ".class") != null) arcProvider = true;
            } catch (Exception unreadable) {
                QuillLog.swallowed(unreadable);
            }
        }
        List<String> parts = new ArrayList<>();
        if (!decorators.isEmpty())
            parts.add("Uni callbacks decorated by " + String.join(", ", decorators) + " (registered service)");
        if (arcProvider) parts.add("CDI context captured by " + ARC_PROVIDER);
        return String.join("; ", parts);
    }

    private static String coordinates(Path jar) {
        // .../group/path/artifact/version/artifact-version.jar
        Path version = jar.getParent(), artifact = version == null ? null : version.getParent();
        if (artifact == null || artifact.getParent() == null) return jar.getFileName().toString();
        List<String> group = new ArrayList<>();
        for (Path p = artifact.getParent();
                p != null && p.getFileName() != null;
                p = p.getParent()) {
            String segment = p.getFileName().toString();
            if (segment.equals("repository") || segment.equals("m2") || segment.equals(".m2"))
                break;
            group.add(0, segment);
        }
        return String.join(".", group) + ":" + artifact.getFileName() + ":" + version.getFileName();
    }

    private static List<Path> jars(ReadQueryContext db, Path root) {
        try {
            return ProjectDependencyQueries.resolvedJars(db, root);
        } catch (RuntimeException unavailable) {
            QuillLog.swallowed(unavailable);
            return List.of();
        }
    }

    private static List<String> source(ReadQueryContext db, Path root, int classId) {
        String file = db.symbols.findSourceFile(classId).orElse(null);
        if (file == null) return List.of();
        try {
            return Files.readAllLines(root.resolve(file));
        } catch (Exception unreadable) {
            QuillLog.swallowed(unreadable);
            return List.of();
        }
    }

    private static Set<String> fields(ReadQueryContext db, int classId) {
        return new HashSet<>(db.symbols.findFieldNames(classId));
    }
}
