package org.treblereel.mcp.mcp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.diagnostics.QuillLog;

/**
 * Documented framework behaviour for the annotations a selected method carries, with the
 * application's own declarations of the settings that behaviour reads. In V21 Codex opened
 * dependency classes 86 times to learn these rules; the rules are documented, so bytecode of the
 * framework would not add to them. Deployed configuration values stay unknown.
 */
public final class FrameworkContracts {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final String BASIS =
            "documented framework behaviour, not read from dependency bytecode; "
                    + "deployed configuration values are unknown";
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}");

    private static final List<String> SCHEDULED =
            List.of(
                    "@Scheduled: the Quarkus scheduler invokes the method; no application caller is needed",
                    "every/cron/delayed accept ${key:default}; a resolved value of off or disabled (case-insensitive) never schedules the method",
                    "quarkus.scheduler.enabled=false disables every scheduled method",
                    "quarkus.scheduler.start-mode: normal starts the scheduler only when scheduled methods exist, forced always starts it, halted starts it paused",
                    "concurrentExecution defaults to PROCEED: a run starts even while the previous one is still running",
                    "a blocking method runs on a worker thread; the CDI request context is active during each run");
    private static final List<String> STARTUP =
            List.of(
                    "@Observes StartupEvent: Arc calls the observer synchronously while the application starts; an exception fails the start",
                    "observers of one event run in @Priority order, lower first (default 2500)");
    private static final List<String> SHUTDOWN =
            List.of(
                    "@Observes ShutdownEvent: Arc calls the observer synchronously on graceful shutdown; a killed or crashed JVM skips it",
                    "observers of one event run in @Priority order, lower first (default 2500)");
    private static final Map<String, String> TX_TYPES =
            Map.of(
                    "REQUIRED",
                            "REQUIRED (default): joins the caller's transaction or begins one; a transaction it began commits when the method returns",
                    "REQUIRES_NEW",
                            "REQUIRES_NEW: suspends a caller's transaction and always begins its own, committed when the method returns",
                    "MANDATORY",
                            "MANDATORY: throws TransactionalException when no transaction is active",
                    "SUPPORTS",
                            "SUPPORTS: joins a caller's transaction, otherwise runs without one",
                    "NOT_SUPPORTED",
                            "NOT_SUPPORTED: suspends a caller's transaction and runs without one",
                    "NEVER", "NEVER: throws TransactionalException when a transaction is active");
    private static final List<String> TRANSACTIONAL =
            List.of(
                    "a RuntimeException or Error rolls back; a checked exception commits unless rollbackOn lists it; dontRollbackOn overrides both",
                    "the transaction covers what the method body runs on its own thread, lambdas included; work handed to another thread is outside it",
                    "the interceptor wraps the call only through a CDI proxy: a self-invocation from the same bean bypasses it");

    /** Spring Boot startup callbacks, by interface. */
    private static final Map<String, String> RUNNERS =
            Map.of(
                    "org.springframework.boot.ApplicationRunner", "ApplicationRunner",
                    "org.springframework.boot.CommandLineRunner", "CommandLineRunner");

    private static final List<String> RUNNER_RULES =
            List.of(
                    "Spring Boot calls it once from SpringApplication#run, after the context is refreshed and before the application is reported ready",
                    "all ApplicationRunner and CommandLineRunner beans run one after another on the starting thread, sorted by @Order/Ordered; beans without an order run last, in an unspecified order among themselves",
                    "an exception thrown here fails the startup; nothing calls it again or on shutdown");
    private static final String ORDER = "org.springframework.core.annotation.Order";
    private static final int PEERS = 6;

    private FrameworkContracts() {}

    public static void append(
            ReadQueryContext db, ObjectNode fact, int classId, String name, String descriptor)
            throws Exception {
        String details =
                db.symbols.findMemberAnnotationDetails(classId, name, descriptor).orElse("[]");
        Map<String, JsonNode> method = new LinkedHashMap<>();
        Set<String> observed = new LinkedHashSet<>();
        org.objectweb.asm.Type[] parameters;
        try {
            parameters = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        } catch (RuntimeException malformed) {
            QuillLog.swallowed(malformed);
            parameters = new org.objectweb.asm.Type[0];
        }
        for (JsonNode annotation : JSON.readTree(details)) {
            String type = annotation.path("annotationName").asText();
            int index = annotation.path("parameterIndex").asInt(-1);
            if (index < 0) method.put(type, annotation.path("values"));
            else if (type.equals("jakarta.enterprise.event.Observes") && index < parameters.length)
                observed.add(parameters[index].getClassName());
        }
        if (!method.containsKey("jakarta.transaction.Transactional")) {
            for (String values :
                    db.symbols.findDirectClassAnnotationValues(
                            classId, "jakarta.transaction.Transactional")) {
                method.put(
                        "jakarta.transaction.Transactional",
                        values == null ? JSON.createObjectNode() : JSON.readTree(values));
            }
        }
        List<String> rules = new ArrayList<>();
        Map<String, String> settings = new LinkedHashMap<>();
        JsonNode scheduled = method.get("io.quarkus.scheduler.Scheduled");
        if (scheduled != null) {
            rules.addAll(SCHEDULED);
            for (String member : List.of("every", "cron", "delayed")) {
                Matcher expression = EXPRESSION.matcher(scheduled.path(member).asText(""));
                while (expression.find())
                    settings.put(
                            expression.group(1),
                            expression.group(2) == null
                                    ? null
                                    : "expression default " + expression.group(2));
            }
            settings.put("quarkus.scheduler.enabled", "framework default true");
            settings.put("quarkus.scheduler.start-mode", "framework default normal");
        }
        if (observed.contains("io.quarkus.runtime.StartupEvent")) rules.addAll(STARTUP);
        if (observed.contains("io.quarkus.runtime.ShutdownEvent"))
            rules.addAll(SHUTDOWN.stream().filter(rule -> !rules.contains(rule)).toList());
        JsonNode transactional = method.get("jakarta.transaction.Transactional");
        if (transactional != null) {
            String value = transactional.path("value").asText("REQUIRED");
            String type =
                    TX_TYPES.keySet().stream()
                            .filter(value::endsWith)
                            .findFirst()
                            .orElse("REQUIRED");
            rules.add("@Transactional " + TX_TYPES.get(type));
            rules.addAll(TRANSACTIONAL);
            settings.put(
                    "quarkus.transaction-manager.default-transaction-timeout",
                    "framework default 60s");
        }
        List<String> peers = new ArrayList<>();
        var owner = db.symbols.findClassById(classId).orElse(null);
        String runner =
                owner == null || !name.equals("run")
                        ? null
                        : owner.interfaces().stream()
                                .map(RUNNERS::get)
                                .filter(java.util.Objects::nonNull)
                                .findFirst()
                                .orElse(null);
        if (runner != null) {
            rules.add(runner + "#run: " + RUNNER_RULES.getFirst());
            rules.addAll(RUNNER_RULES.subList(1, RUNNER_RULES.size()));
            peers.addAll(runnerPeers(db, owner));
        }
        if (rules.isEmpty()) return;
        ObjectNode contract = fact.putObject("framework_contract");
        contract.put("basis", BASIS);
        rules.forEach(contract.withArray("rules")::add);
        if (runner != null)
            contract.set(
                    "other_runners",
                    peers.isEmpty()
                            ? JSON.createArrayNode().add("none in module " + owner.module())
                            : JSON.valueToTree(peers));
        if (!settings.isEmpty()) contract.set("configuration", declarations(db, settings));
    }

    /**
     * The module's other startup runners with their order, so the relative order needs no search.
     * Apollo AQ-035: Codex listed ApplicationRunner subtypes and read
     * SpringApplication#callRunners.
     */
    private static List<String> runnerPeers(
            ReadQueryContext db, org.treblereel.mcp.model.ClassRecord owner) {
        List<String> peers = new ArrayList<>();
        int total = 0;
        for (var row : db.symbols.findMainSourceClassInterfaces()) {
            if (row.className().equals(owner.className())) continue;
            String kind =
                    RUNNERS.entrySet().stream()
                            .filter(
                                    e ->
                                            String.valueOf(row.interfaces())
                                                    .contains("\"" + e.getKey() + "\""))
                            .map(Map.Entry::getValue)
                            .findFirst()
                            .orElse(null);
            if (kind == null) continue;
            var peer = db.symbols.findClassByName(row.className()).orElse(null);
            if (peer == null || !java.util.Objects.equals(peer.module(), owner.module())) continue;
            if (++total > PEERS) continue;
            String order = null;
            for (String values : db.symbols.findDirectClassAnnotationValues(peer.id(), ORDER)) {
                try {
                    JsonNode value = values == null ? null : JSON.readTree(values).path("value");
                    order =
                            value == null || value.isMissingNode()
                                    ? "@Order"
                                    : "@Order(" + value.asText() + ")";
                } catch (Exception unreadable) {
                    QuillLog.swallowed(unreadable);
                    order = "@Order";
                }
            }
            String simple = row.className().substring(row.className().lastIndexOf('.') + 1);
            peers.add(simple + " " + (order != null ? order : kind + ", no @Order"));
        }
        if (total > PEERS) peers.add("+" + (total - PEERS) + " more");
        return peers;
    }

    /**
     * One line per key: each declaration with its value, or the default when nothing declares it.
     */
    private static ArrayNode declarations(ReadQueryContext db, Map<String, String> settings) {
        ArrayNode lines = JSON.createArrayNode();
        ConfigurationSourceEvidence source = new ConfigurationSourceEvidence(db);
        for (var setting : settings.entrySet()) {
            String key = setting.getKey();
            var rows = db.references.findPropertyDeclarations(key);
            for (var row : rows) {
                ObjectNode reference = JSON.createObjectNode();
                String declared = row.key(), file = row.file();
                int line = row.line().intValue();
                source.append(
                        reference,
                        declared,
                        file,
                        line,
                        row.kind(),
                        String.valueOf(row.evidence()));
                JsonNode evidence = reference.path("declared_source");
                String value =
                        evidence.has("declared_value")
                                ? evidence.path("declared_value").asText()
                                : evidence.path("status")
                                                .asText()
                                                .equals("opaque_or_unsupported_value")
                                        ? enumWord(db, file, declared, line)
                                        : null;
                lines.add(
                        value != null
                                ? declared + "=" + value + " " + file + ":" + line
                                : declared
                                        + " declared at "
                                        + file
                                        + ":"
                                        + line
                                        + " (value "
                                        + evidence.path("status").asText("unknown")
                                        + ")");
            }
            boolean profileOnly =
                    rows.stream().allMatch(row -> String.valueOf(row.key()).startsWith("%"));
            if (profileOnly)
                lines.add(
                        key
                                + ": "
                                + (rows.isEmpty()
                                        ? "not declared; "
                                        : "no unprofiled declaration; ")
                                + (setting.getValue() == null ? "no default" : setting.getValue()));
        }
        return lines;
    }

    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z_-]{0,30}");

    /** A framework setting's enum constant such as {@code forced}; anything else stays unshown. */
    private static String enumWord(ReadQueryContext db, String file, String key, int line) {
        String projectRoot = db.index.getMetadata().get("project_root");
        if (projectRoot == null || !file.endsWith(".properties")) return null;
        try {
            java.nio.file.Path root = java.nio.file.Path.of(projectRoot).toRealPath(),
                    path = root.resolve(file).toRealPath();
            if (!path.startsWith(root) || java.nio.file.Files.size(path) > 1_000_000) return null;
            return org.treblereel.mcp.core.ConfigurationScanner.declaredProperties(
                            java.nio.file.Files.readAllLines(path), false)
                    .stream()
                    .filter(property -> property.key().equals(key) && property.line() == line)
                    .map(org.treblereel.mcp.core.ConfigurationScanner.DeclaredProperty::value)
                    .filter(value -> WORD.matcher(value).matches())
                    .findFirst()
                    .orElse(null);
        } catch (Exception unreadable) {
            QuillLog.swallowed(unreadable);
            return null;
        }
    }
}
