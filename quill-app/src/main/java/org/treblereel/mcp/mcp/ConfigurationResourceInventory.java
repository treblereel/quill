package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;

/** Current names only: never reads configuration contents or environment values. */
final class ConfigurationResourceInventory {
    private static final ObjectMapper JSON=new ObjectMapper();
    static ObjectNode collect(Jdbi db){
        var result=JSON.createObjectNode().put("status","ready")
            .put("scope","Current properties/YAML/env paths in standard main/test roots of indexed modules; custom/external/runtime unproven")
            .put("file_limit",64).put("entry_limit",2048).put("depth_limit",12).put("module_limit",32).put("truncated",false);
        var files=result.putArray("files");var missing=result.putArray("unavailable");
        try {
            Path root=Path.of(IndexReader.getMetadata(db).get("project_root")).toRealPath();
            var modules=db.withHandle(h->h.createQuery("SELECT DISTINCT module FROM classes WHERE lifecycle='current' AND origin='source' AND module IS NOT NULL ORDER BY module LIMIT 33").mapTo(String.class).list());
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
    private ConfigurationResourceInventory(){}
}
