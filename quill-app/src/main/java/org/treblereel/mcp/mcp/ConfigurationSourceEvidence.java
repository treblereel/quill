package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.ConfigurationScanner;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.core.QuarkusTestProfileOverrides;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.treblereel.mcp.db.IndexReader;

/** Explicit, bounded current-source evidence; never evaluates expressions or reads environment values. */
final class ConfigurationSourceEvidence {
    private static final Pattern SENSITIVE = Pattern.compile("(?i)(^|[._-])(password|passwd|secret|token|credential[s]?|private[._-]?key|api[._-]?key|access[._-]?key|pin)([._-]|$)");
    private static final Pattern SCALAR = Pattern.compile("(?i)(true|false|[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:ns|us|ms|s|m|h|d|[kmgt]i?b?)?)");
    private final String projectRoot;
    private final Map<Path, Snapshot> files = new LinkedHashMap<>();
    private int definitions;
    private record BinarySnapshot(String hash,QuarkusTestProfileOverrides.Parsed parsed) {}
    private final Map<Path,BinarySnapshot> binaries=new LinkedHashMap<>();
    private record Snapshot(String hash, List<ConfigurationScanner.DeclaredProperty> properties) {}
    ConfigurationSourceEvidence(Jdbi db) { projectRoot = IndexReader.getMetadata(db).get("project_root"); }

    void append(ObjectNode reference, String key, String filename, int line, String kind) { append(reference,key,filename,line,kind,"{}"); }
    void append(ObjectNode reference, String key, String filename, int line, String kind,String provenance) {
        ObjectNode evidence = reference.putObject("declared_source").put("source_complete", false)
                .put("effective_value_proven", false);
        if (!Set.of("property", "yaml_property","quarkus_test_profile").contains(kind)) { evidence.put("status", "unsupported_source_kind"); return; }
        String profile = key.startsWith("%") && key.indexOf('.') > 1 ? key.substring(1, key.indexOf('.')) : "default";
        if(!kind.equals("quarkus_test_profile")) reference.put("base_key", baseKey(key)).put("key_profile", profile);
        String name = Path.of(filename).getFileName().toString();
        if(!kind.equals("quarkus_test_profile")) reference.put("file_profile", name.startsWith("application-") && name.lastIndexOf('.') > 12
                ? name.substring(12, name.lastIndexOf('.')) : "default_or_custom_source");
        if (++definitions > 12) { evidence.put("status", "packet_source_limit"); return; }
        if (projectRoot == null) { evidence.put("status", "project_root_unknown"); return; }
        try {
            Path root = Path.of(projectRoot).toRealPath(), file = root.resolve(filename).toRealPath();
            if (!file.startsWith(root)) { evidence.put("status", "source_outside_root"); return; }
            boolean yaml = name.endsWith(".yml") || name.endsWith(".yaml");
            String value;
            if(kind.equals("quarkus_test_profile")) {
                value=profileValue(evidence,key,root,file,line,provenance);
                if(value==null)return;
            } else {
            if (!yaml && !name.endsWith(".properties")) { evidence.put("status", "unsupported_source_kind"); return; }
            Snapshot snapshot = files.get(file);
            if (snapshot == null) {
                if (files.size() >= 5 || Files.size(file) > 1_000_000) { evidence.put("status", "source_file_limit"); return; }
                String hash = SourceFileHashes.hash(file);
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > 1_000_000) { evidence.put("status", "source_file_limit"); return; }
                var properties = ConfigurationScanner.declaredProperties(new String(bytes, StandardCharsets.UTF_8).lines().toList(), yaml);
                if (!hash.equals(SourceFileHashes.hash(file))) { evidence.put("status", "inputs_changed"); return; }
                snapshot = new Snapshot(hash, properties); files.put(file, snapshot);
            }
            if (!snapshot.hash().equals(SourceFileHashes.hash(file))) { evidence.put("status", "inputs_changed"); return; }
            value = snapshot.properties().stream().filter(p -> p.key().equals(key) && p.line() == line)
                    .map(ConfigurationScanner.DeclaredProperty::value).findFirst().orElse(null);
            evidence.put("source_sha256", snapshot.hash()).put("basis", "current key at indexed line");
            if (value == null) { evidence.put("status", "definition_line_changed"); return; }
            }
            if (SENSITIVE.matcher(baseKey(key).replaceAll("([a-z])([A-Z])", "$1.$2")).find()) { evidence.put("status", "redacted_key"); return; }
            if (value.contains("${")) { evidence.put("status", "expression_unresolved"); return; }
            if (value.length() > 80 || !scalar(value)) { evidence.put("status", "opaque_or_unsupported_value"); return; }
            evidence.put("status", "declared_scalar").put("declared_value", value).put("source_complete", true);
        } catch (Exception unavailable) { evidence.put("status", "source_unavailable"); }
    }
    private String profileValue(ObjectNode evidence,String key,Path root,Path source,int line,String provenance) throws Exception {
        var info=new ObjectMapper().readTree(provenance);
        if(line<=0){evidence.put("status","source_line_unknown");return null;}
        if(!files.containsKey(source)) {
            if(files.size()>=5 || Files.size(source)>1_000_000){evidence.put("status","source_file_limit");return null;}
            files.put(source,new Snapshot(SourceFileHashes.hash(source),List.of()));
        }
        String hash=files.get(source).hash();
        if(!hash.equals(info.path("source_sha256").asText()) || !hash.equals(SourceFileHashes.hash(source))){evidence.put("status","inputs_changed");return null;}
        Path binary=root.resolve(info.path("binary_file").asText()).toRealPath();
        if(!binary.startsWith(root)){evidence.put("status","binary_outside_root");return null;}
        if(!binaries.containsKey(binary)) {
            if(binaries.size()>=5 || Files.size(binary)>1_000_000){evidence.put("status","source_file_limit");return null;}
            String binaryHash=SourceFileHashes.hash(binary);
            var parsed=QuarkusTestProfileOverrides.read(Files.readAllBytes(binary));
            if(!binaryHash.equals(SourceFileHashes.hash(binary))){evidence.put("status","inputs_changed");return null;}
            binaries.put(binary,new BinarySnapshot(binaryHash,parsed));
        }
        var captured=binaries.get(binary);
        if(!captured.hash().equals(info.path("binary_sha256").asText()) || !captured.hash().equals(SourceFileHashes.hash(binary))){evidence.put("status","compiled_inputs_changed");return null;}
        if(!captured.parsed().status().equals("static_map")){evidence.put("status","unsupported_profile_body");return null;}
        String value=captured.parsed().values().stream().filter(v->v.key().equals(key) && v.line()==line).map(QuarkusTestProfileOverrides.Value::value).findFirst().orElse(null);
        evidence.put("source_sha256",hash).put("binary_sha256",captured.hash()).put("basis","literal compiled map with matching indexed source and bytecode hashes");
        if(value==null)evidence.put("status","indexed_entry_changed");
        return value;
    }
    void finish(ObjectNode result) {
        boolean stable = true;
        for (var entry : files.entrySet()) {
            try { stable &= entry.getValue().hash().equals(SourceFileHashes.hash(entry.getKey())); }
            catch (Exception unavailable) { stable = false; }
        }
        for(var entry:binaries.entrySet()) {
            try {stable &= entry.getValue().hash().equals(SourceFileHashes.hash(entry.getKey()));}
            catch(Exception unavailable){stable=false;}
        }
        if (!stable) for (var reference : result.path("references")) {
            if (reference.has("declared_source")) ((ObjectNode) reference).putObject("declared_source")
                    .put("status", "inputs_changed").put("source_complete", false).put("effective_value_proven", false);
        }
        result.put("source_definition_limit", 12).put("source_file_limit", 5)
                .put("effective_runtime_values_proven", false);
    }
    static String baseKey(String key) { return key.startsWith("%") && key.indexOf('.') > 1 ? key.substring(key.indexOf('.') + 1) : key; }
    private static boolean scalar(String value) {
        if (SCALAR.matcher(value).matches()) return true;
        if (!value.startsWith("P")) return false;
        try { Duration.parse(value); return true; } catch (Exception unsupported) { return false; }
    }
}
