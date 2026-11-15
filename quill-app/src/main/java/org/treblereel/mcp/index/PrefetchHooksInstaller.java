package org.treblereel.mcp.index;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.treblereel.mcp.core.Json;

/** Project-local hook wiring; records exact owned commands and preserves unrelated handlers. */
public final class PrefetchHooksInstaller {
    private static final ObjectMapper JSON=Json.MAPPER;
    private static final List<String> FILES=List.of(".codex/hooks.json",".claude/settings.json");
    public static boolean install(Path root,String binary) {
        if(binary==null || !Path.of(binary).isAbsolute())return false;
        try {
            List<ObjectNode> documents=new ArrayList<>();
            for(String name:FILES) {
                var doc=ClaudeSettingsInstaller.readPermissionSettings(root.resolve(name));
                if(doc==null || doc.has("hooks") && !doc.path("hooks").isObject())return false;
                for(String event:List.of("SessionStart","UserPromptSubmit"))
                    if(doc.path("hooks").has(event) && !doc.path("hooks").path(event).isArray())return false;
                documents.add(doc);
            }
            Path receipt=root.resolve(".quill/prefetch-hooks.json");
            Set<String> owned=new HashSet<>();
            if(Files.isRegularFile(receipt))for(var command:JSON.readTree(receipt.toFile()).path("commands"))owned.add(command.asText());
            String command=quote(binary)+" prefetch --project "+quote(root.toAbsolutePath().normalize().toString());
            owned.add(command);
            for(int i=0;i<FILES.size();i++) {
                ObjectNode doc=documents.get(i);ObjectNode hooks=doc.has("hooks")?(ObjectNode)doc.get("hooks"):doc.putObject("hooks");
                removeOwned(hooks,owned);
                for(String event:List.of("SessionStart","UserPromptSubmit")) {
                    ArrayNode groups=hooks.has(event)?(ArrayNode)hooks.get(event):hooks.putArray(event);
                    ObjectNode group=groups.addObject();if(event.equals("SessionStart"))group.put("matcher","startup|clear|compact|fork");
                    // Cold source-range analysis can exceed ten seconds on large projects.
                    group.putArray("hooks").addObject().put("type","command").put("command",command).put("timeout",event.equals("SessionStart")?10:30);
                }
                ClaudeSettingsInstaller.writeAtomically(root.resolve(FILES.get(i)),doc);
            }
            ObjectNode data=JSON.createObjectNode();data.putArray("commands").add(command);
            ClaudeSettingsInstaller.writeAtomically(receipt,data);return true;
        }catch(Exception unavailable){System.err.println("[quill] Prefetch hooks not updated: unsupported configuration or unavailable path.");return false;}
    }
    public static void uninstall(Path root) throws java.io.IOException {
        Path receipt=root.resolve(".quill/prefetch-hooks.json");if(!Files.isRegularFile(receipt))return;
        Set<String> owned=new HashSet<>();for(var c:JSON.readTree(receipt.toFile()).path("commands"))owned.add(c.asText());
        for(String file:FILES) {
            var doc=ClaudeSettingsInstaller.readPermissionSettings(root.resolve(file));if(doc==null || !doc.path("hooks").isObject())continue;
            removeOwned((ObjectNode)doc.get("hooks"),owned);ClaudeSettingsInstaller.writeAtomically(root.resolve(file),doc);
        }
        Files.deleteIfExists(receipt);
    }
    private static void removeOwned(ObjectNode hooks,Set<String> commands) {
        for(String event:List.of("SessionStart","UserPromptSubmit"))if(hooks.path(event).isArray()) {
            ArrayNode groups=(ArrayNode)hooks.get(event);
            for(JsonNode group:groups)if(group.path("hooks").isArray())
                ((ArrayNode)group.get("hooks")).removeIf(h->commands.contains(h.path("command").asText()));
            groups.removeIf(g->g.path("hooks").isArray() && g.path("hooks").isEmpty());
        }
    }
    private static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
    private PrefetchHooksInstaller(){}
}
