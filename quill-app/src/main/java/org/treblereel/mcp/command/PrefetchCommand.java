package org.treblereel.mcp.command;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.index.ProjectIndexStore;
import org.treblereel.mcp.mcp.prefetch.PrefetchContext;
import picocli.CommandLine.*;

/**
 * Read an existing index only. This hook never initializes MCP, runs builds or calls a provider.
 */
@Command(
        name = "prefetch",
        mixinStandardHelpOptions = true,
        description =
                "Read bounded indexed facts for literal symbols or HTTP operations from a client hook")
public final class PrefetchCommand implements Callable<Integer> {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .enable(
                            com.fasterxml.jackson.core.JsonParser.Feature
                                    .STRICT_DUPLICATE_DETECTION);

    @Option(names = "--project", required = true)
    Path root;

    @Override
    public Integer call() {
        try {
            byte[] input = System.in.readNBytes(1_000_001);
            if (input.length > 1_000_000) return 0;
            JsonNode hook = JSON.readTree(input);
            String event = hook.path("hook_event_name").asText();
            if (!event.equals("SessionStart") && !event.equals("UserPromptSubmit")) return 0;
            root = root.toRealPath();
            if (hook.hasNonNull("cwd")
                    && !Path.of(hook.path("cwd").asText()).toRealPath().startsWith(root)) return 0;
            String session = hook.path("session_id").asText();
            if (session.isBlank() || session.length() > 512) return 0;
            Path directory = root.resolve(".quill/prefetch");
            if (Files.isSymbolicLink(root.resolve(".quill")) || Files.isSymbolicLink(directory))
                return 0;
            Files.createDirectories(directory);
            Path state = directory.resolve(digest(session) + ".json"),
                    lock = directory.resolve(digest(session) + ".lock");
            if (!directory.toRealPath().startsWith(root)
                    || Files.isSymbolicLink(state)
                    || Files.isSymbolicLink(lock)) return 0;
            try (var channel =
                            FileChannel.open(
                                    lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var guard = channel.tryLock()) {
                if (guard == null) return 0;
                if (event.equals("SessionStart")) {
                    Files.deleteIfExists(state);
                    return 0;
                }
                Path index = ProjectIndexStore.findBestAvailableDb(root);
                if (index == null) return 0;
                ObjectNode delivered =
                        Files.isRegularFile(state)
                                ? (ObjectNode) JSON.readTree(state.toFile())
                                : JSON.createObjectNode();
                ObjectNode data;
                try (var db =
                        org.treblereel.mcp.db.ReadQueryContext.openWithGraph(
                                QuillDatabase.open(index))) {
                    data =
                            PrefetchContext.collect(
                                    db,
                                    root,
                                    hook.path("prompt").asText(),
                                    delivered.path("_endpoint").asText(null),
                                    db.index
                                            .getMetadata()
                                            .getOrDefault("index_id", "")
                                            .equals(delivered.path("_index_id").asText()));
                    // A session's first prompt that names nothing indexed still gets the project's
                    // shape once.
                    if (!delivered.has("_index_id")
                            && "ready".equals(data.path("status").asText())
                            && data.path("facts").isEmpty()
                            && data.path("unavailable").isEmpty()
                            && data.path("ambiguities").isEmpty())
                        data.withArray("facts")
                                .add(
                                        org.treblereel.mcp.mcp.prefetch.NameTargetPrefetch
                                                .projectFact(db, root));
                }
                // shortcut: cache tracks stdout emission, not host acknowledgement; verify delivery
                // in future CLI captures.
                if (!data.path("index_id").asText().equals(delivered.path("_index_id").asText()))
                    delivered.removeAll();
                delivered.put("_index_id", data.path("index_id").asText());
                if (data.has("endpoint_anchor"))
                    delivered.set("_endpoint", data.get("endpoint_anchor"));
                else delivered.remove("_endpoint");
                ArrayNode fresh = JSON.createArrayNode();
                for (JsonNode fact : data.path("facts")) {
                    ObjectNode identity = ((ObjectNode) fact).deepCopy();
                    identity.remove("_meta");
                    String id = digest(data.path("index_id").asText() + identity);
                    if (!delivered.has(id)) fresh.add(fact);
                }
                data.set("facts", fresh);
                if (!fresh.isEmpty() && "ready".equals(data.path("status").asText()))
                    data.put("producer", "quill.prefetch")
                            .put(
                                    "instructions",
                                    org.treblereel.mcp.mcp.prefetch.PrefetchRestatements
                                            .instructions(fresh));
                PrefetchContext.boundContext(data, hook.path("prompt").asText(""));
                // An omitted context was not acknowledged: a prompt naming the same target gets it
                // again. The session and its operation anchor go on, though (Apollo AQ-050: a reset
                // after a packet lost only its descriptor sent the next step the project overview
                // and the later steps nothing).
                boolean truncated = data.path("context_truncated").asBoolean();
                if (!truncated)
                    for (JsonNode fact : fresh) {
                        ObjectNode identity = ((ObjectNode) fact).deepCopy();
                        identity.remove("_meta");
                        delivered.put(digest(data.path("index_id").asText() + identity), true);
                    }
                if (truncated && data.path("facts").isEmpty()) {
                    delivered.remove("_index_id");
                    delivered.remove("_endpoint");
                }
                if (delivered.size() > 512) delivered = JSON.createObjectNode();
                Path temporary = Files.createTempFile(directory, "pending-", ".json");
                try {
                    JSON.writeValue(temporary.toFile(), delivered);
                    Files.move(
                            temporary,
                            state,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                if (fresh.isEmpty()
                        && data.path("unavailable").isEmpty()
                        && data.path("ambiguities").isEmpty()
                        && "ready".equals(data.path("status").asText())) return 0;
                PrefetchContext.stateRepeatsOnce(data);
                ObjectNode output = JSON.createObjectNode();
                output.putObject("hookSpecificOutput")
                        .put("hookEventName", event)
                        .put("additionalContext", data.toString());
                System.out.println(output);
            }
        } catch (Exception unavailable) {
            System.err.println("[quill] Prefetch unavailable; use MCP for current evidence.");
        }
        return 0;
    }

    private static String digest(String text) throws Exception {
        return HexFormat.of()
                .formatHex(
                        MessageDigest.getInstance("SHA-256")
                                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
