package org.treblereel.mcp.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.core.ConfigurationScanner;
import org.treblereel.mcp.core.SourceFileHashes;

/** Current resource inventory and bounded properties-key coverage; no process environment reads. */
public final class ConfigurationResourceInventory {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static ObjectNode collect(ReadQueryContext db){
        var result=JSON.createObjectNode().put("status","ready")
            .put("scope","Current properties/YAML/env paths in standard main/test roots of indexed modules; custom/external/runtime unproven")
            .put("file_limit",64).put("entry_limit",2048).put("depth_limit",12).put("module_limit",32).put("truncated",false);
        var files=result.putArray("files");var missing=result.putArray("unavailable");
        try {
            Path root=Path.of(db.index.getMetadata().get("project_root")).toRealPath();
            var modules=db.symbols.findSourceModules();
            if(modules.isEmpty())modules=List.of(".");
            if(modules.size()>32)result.put("status","partial").put("truncated",true);
            Set<String> found=new TreeSet<>();Map<Path,BasicFileAttributes> directories=new LinkedHashMap<>();int[] visited={0};
            for(String module:modules.stream().limit(32).toList())for(String sourceSet:List.of("main","test")) {
                Path directory=root.resolve(module).resolve("src/"+sourceSet+"/resources").normalize();
                if(!directory.startsWith(root))throw new IOException("resource_root_outside_project");
                if(Files.notExists(directory))continue;
                try {
                    if(Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(root))throw new IOException("resource_symlink_or_outside_root");
                    Files.walkFileTree(directory,EnumSet.noneOf(FileVisitOption.class),12,new SimpleFileVisitor<>(){
                        private FileVisitResult budget(){if(++visited[0]>2048 || found.size()>=64){result.put("status","partial").put("truncated",true);return FileVisitResult.TERMINATE;}return FileVisitResult.CONTINUE;}
                        @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs){var action=budget();if(action==FileVisitResult.CONTINUE)directories.put(path,attrs);return action;}
                        @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs){
                            var action=budget();if(action!=FileVisitResult.CONTINUE)return action;
                            if(attrs.isSymbolicLink() || attrs.isDirectory()){result.put("status","partial");missing.addObject().put("path",root.relativize(path).toString()).put("status",attrs.isDirectory()?"depth_limit":"symlink_not_followed");return FileVisitResult.CONTINUE;}
                            String name=path.getFileName().toString().toLowerCase(Locale.ROOT);
                            if(name.endsWith(".properties") || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".env"))found.add(root.relativize(path).toString().replace('\\','/'));
                            return FileVisitResult.CONTINUE;
                        }
                        @Override public FileVisitResult visitFileFailed(Path path,IOException error){result.put("status","partial");missing.addObject().put("path",root.relativize(path).toString()).put("status","unreadable");return FileVisitResult.CONTINUE;}
                    });
                }catch(IOException unavailable){result.put("status","partial");missing.addObject().put("path",root.relativize(directory).toString()).put("status","resource_root_unavailable");}
                if(result.path("truncated").asBoolean())break;
            }
            for(var entry:directories.entrySet()){
                var now=Files.readAttributes(entry.getKey(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!Objects.equals(entry.getValue().fileKey(),now.fileKey()) || !entry.getValue().lastModifiedTime().equals(now.lastModifiedTime()))throw new IOException("directory_changed");
            }
            found.forEach(files::add);
        }catch(Exception unavailable){files.removeAll();result.put("status","unavailable");missing.addObject().put("status","inventory_unavailable_or_changed");}
        return result;
    }
    /** Empty matches prove absence only in this successfully parsed current file, never at runtime. */
    public static ObjectNode collect(ReadQueryContext db,String key){
        ObjectNode inventory=collect(db);
        var result=JSON.createObjectNode().put("status",inventory.path("status").asText())
                .put("scope","Listed properties; matches=key-profile:lines (empty=checked absence); custom/external/env/runtime unknown")
                .put("truncated",inventory.path("truncated").asBoolean());
        var files=result.putObject("files");result.set("unavailable",inventory.path("unavailable"));
        try {
            Path root=Path.of(db.index.getMetadata().get("project_root")).toRealPath();int bytes=0;
            for(var filename:inventory.path("files")){
                String name=filename.asText();var row=files.putObject(name);
                try {
                    Path path=root.resolve(name).normalize();
                    if(!path.startsWith(root) || Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root))throw new IOException("outside_or_symlink");
                    if(!name.endsWith(".properties"))throw new IOException("unsupported_format");
                    int remaining=Math.min(1_000_000,2_000_000-bytes);
                    if(Files.size(path)>remaining)throw new IOException("source_limit");
                    String hash=SourceFileHashes.hash(path);byte[] content;
                    try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){content=input.readNBytes(remaining+1);}
                    bytes+=content.length;
                    if(content.length>1_000_000 || bytes>2_000_000)throw new IOException("source_limit");
                    if(!hash.equals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content))))throw new IOException("inputs_changed");
                    String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString();
                    new Properties().load(new StringReader(text));
                    var properties=ConfigurationScanner.declaredProperties(text.lines().toList(),false);
                    if(!hash.equals(SourceFileHashes.hash(path)))throw new IOException("inputs_changed");
                    row.put("sha256",hash);var matches=row.putObject("matches");int count=0;
                    for(var property:properties)if(ConfigurationSourceEvidence.baseKey(property.key()).equals(ConfigurationSourceEvidence.baseKey(key))){
                        if(++count>64)throw new IOException("match_limit");
                        String profile=property.key().startsWith("%")?property.key().substring(0,property.key().indexOf('.')):"default";
                        matches.withArray(profile).add(property.line());
                    }
                }catch(Exception unavailable){row.removeAll();row.put("status","unknown").put("reason",unavailable instanceof IOException?unavailable.getMessage():"parse_failed");result.put("status","partial");}
            }
            for(var it=files.fields();it.hasNext();){
                var entry=it.next();var row=(ObjectNode)entry.getValue();
                if(!row.has("sha256"))continue;
                try {Path path=root.resolve(entry.getKey());if(Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root) || !row.path("sha256").asText().equals(SourceFileHashes.hash(path)))throw new IOException();}
                catch(Exception changed){row.removeAll();row.put("status","unknown").put("reason","inputs_changed");result.put("status","partial");}
            }
            var current=collect(db);
            if(!inventory.path("files").equals(current.path("files")) || !current.path("status").asText().equals("ready")){result.put("status","partial");result.withArray("unavailable").addObject().put("status","inventory_changed_or_partial");}
        }catch(Exception unavailable){files.removeAll();result.put("status","unavailable");}
        if(result.path("unavailable").isEmpty())result.remove("unavailable");
        return result;
    }
    private ConfigurationResourceInventory(){}
}
