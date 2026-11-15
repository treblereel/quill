package org.treblereel.mcp.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.model.MetaEnvelope;

/** Neighbouring source lines, not inferred method/branch ranges or runtime behaviour. */
public final class ConfigurationReadSnippets {
    private static final ObjectMapper JSON=new ObjectMapper();
    public static void append(ReadQueryContext db,ObjectNode result,Map<String,SortedSet<Integer>> requested) {
        if(requested.isEmpty())return;
        var context=result.putObject("field_read_source").put("status","partial")
                .put("scope","bounded neighbouring source lines around indexed bytecode reads; may omit branches and declarations")
                .put("source_bytecode_identity_proven",false).put("method_complete",false)
                .put("line_limit",80).put("character_limit",9000).put("window_limit",8).put("file_limit",4);
        var files=context.putArray("files");var unavailable=context.putArray("unavailable");
        Map<Path,String> observed=new LinkedHashMap<>();int usedLines=0,usedChars=0,windows=0;boolean truncated=false;
        try {
            if(org.treblereel.mcp.index.BuildStatusInspector.freshness(db,0,0).structureStale()){context.put("status","index_stale");return;}
            var metadata=db.index.getMetadata();
            if(!metadata.containsKey("project_root")){context.put("status","project_root_unknown");return;}
            Path root=Path.of(metadata.get("project_root")).toRealPath();
            var hashes=JSON.readTree(metadata.getOrDefault("java_source_hashes","{}"));
            for(var entry:requested.entrySet()) {
                if(files.size()>=4 || windows>=8 || usedLines>=80 || usedChars>=9000){truncated=true;break;}
                String name=entry.getKey();
                try {
                    if(!name.endsWith(".java"))throw new IllegalArgumentException("java_source_only");
                    Path file=root.resolve(name).toRealPath();
                    if(!file.startsWith(root))throw new IllegalArgumentException("source_outside_root");
                    String relative=root.relativize(file).toString().replace('\\','/');String expected=hashes.path(relative).asText();
                    if(expected.isBlank())throw new IllegalArgumentException("indexed_source_hash_unavailable");
                    byte[] bytes;try(var input=Files.newInputStream(file)){bytes=input.readNBytes(1_000_001);}
                    if(bytes.length>1_000_000)throw new IllegalArgumentException("source_file_limit");
                    String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if(!expected.equals(hash) || !hash.equals(SourceFileHashes.hash(file)))throw new IllegalArgumentException("source_changed_after_index");
                    observed.put(file,hash);var lines=new String(bytes,StandardCharsets.UTF_8).lines().toList();
                    var ranges=new ArrayList<int[]>();
                    for(int n:entry.getValue()) {
                        if(n<1 || n>lines.size()){unavailable.addObject().put("file",relative).put("status","read_line_outside_source");continue;}
                        int first=Math.max(1,n-3),last=Math.min(lines.size(),n+5);
                        if(!ranges.isEmpty() && first<=ranges.getLast()[1]+1)ranges.getLast()[1]=Math.max(last,ranges.getLast()[1]);
                        else ranges.add(new int[]{first,last});
                    }
                    if(ranges.isEmpty())continue;
                    var output=files.addObject().put("file",relative).put("source_sha256",hash);var excerpts=output.putArray("windows");
                    for(var range:ranges) {
                        if(windows>=8 || usedLines>=80){truncated=true;break;}
                        StringBuilder text=new StringBuilder();int end=range[0]-1;int priorLines=usedLines,priorChars=usedChars;
                        for(int n=range[0];n<=range[1];n++) {
                            String line=lines.get(n-1)+"\n";
                            if(usedLines>=80 || usedChars+line.length()>9000){truncated=true;break;}
                            text.append(line);usedLines++;usedChars+=line.length();end=n;
                        }
                        final int lastEmitted=end;
                        if(end<range[0] || entry.getValue().stream().noneMatch(n->n>=range[0] && n<=lastEmitted)) {
                            usedLines=priorLines;usedChars=priorChars;truncated=true;
                            unavailable.addObject().put("file",relative).put("status","read_window_exceeds_packet_budget");break;
                        }
                        excerpts.addObject().put("start_line",range[0]).put("end_line",end).put("source",text.toString());windows++;
                        if(end<range[1])break;
                    }
                } catch(Exception unavailableFile) {
                    String status=unavailableFile instanceof IllegalArgumentException?unavailableFile.getMessage():"source_unavailable";
                    unavailable.addObject().put("file",name).put("status",status);
                }
            }
            for(var entry:observed.entrySet())if(!entry.getValue().equals(SourceFileHashes.hash(entry.getKey()))) {
                files.removeAll();context.put("status","inputs_changed");return;
            }
            if(org.treblereel.mcp.index.BuildStatusInspector.freshness(db,0,0).structureStale()){files.removeAll();context.put("status","index_stale");return;}
            context.put("truncated",truncated).put("status",truncated || !unavailable.isEmpty()?"partial":"ready");
        } catch(Exception unavailableSource) {files.removeAll();context.put("status","source_unavailable");}
    }
}
