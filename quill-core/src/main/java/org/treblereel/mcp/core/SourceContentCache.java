package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;

/** SHA reuse only on local volumes with a nanosecond change-time and stable inode identity. */
final class SourceContentCache {
    private static final Set<String> LOCAL_VOLUMES=Set.of("apfs","hfs","ext4","xfs","btrfs","tmpfs","overlay");
    private record Token(Object key,Object device,long size,FileTime modified,FileTime changed) {}
    private record Entry(Token token,String hash) {}
    private static final Map<Path,Entry> ENTRIES=new LinkedHashMap<>(16,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Path,SourceContentCache.Entry> entry){return size()>4096;}
    };
    private static final ConcurrentHashMap<Path,CompletableFuture<Entry>> PENDING=new ConcurrentHashMap<>();
    record Volume(Object device,boolean supported) {}
    static Volume volume(Path root) {
        try {return new Volume(Files.getAttribute(root,"unix:dev"),LOCAL_VOLUMES.contains(Files.getFileStore(root).type().toLowerCase(Locale.ROOT)));}
        catch(IOException|UnsupportedOperationException|IllegalArgumentException unknown){return new Volume(null,false);}
    }
    private static Token token(Path file) throws IOException {
        var attributes=Files.readAttributes(file,"unix:fileKey,dev,size,lastModifiedTime,ctime");
        return new Token(attributes.get("fileKey"),attributes.get("dev"),((Number)attributes.get("size")).longValue(),
                (FileTime)attributes.get("lastModifiedTime"),(FileTime)attributes.get("ctime"));
    }
    @FunctionalInterface interface Reader {String read(Path file) throws IOException;}
    static String hash(Path file,Volume volume) throws IOException {return hash(file,volume,SourceFileHashes::hash);}
    static String hash(Path file,Volume volume,Reader reader) throws IOException {
        if(!volume.supported())return reader.read(file);
        Token before;
        try {before=token(file);}catch(UnsupportedOperationException|IllegalArgumentException unsupported){return reader.read(file);}
        if(before.key()==null || !Objects.equals(volume.device(),before.device()) || before.changed().toInstant().getNano()==0)
            return reader.read(file);
        synchronized(ENTRIES){var entry=ENTRIES.get(file);if(entry!=null && entry.token().equals(before))return entry.hash();}
        var future=new CompletableFuture<Entry>();var existing=PENDING.putIfAbsent(file,future);
        if(existing!=null) {
            try {
                Entry entry=existing.get();
                if(entry.token().equals(token(file)))return entry.hash();
                // A completed observation is not evidence for a newer write.
                return reader.read(file);
            } catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("Interrupted source observation",error);}
            catch(ExecutionException error){throw new IOException("Concurrent source observation failed",error.getCause());}
        }
        try {
            String hash=reader.read(file);Token after=token(file);
            if(!before.equals(after))throw new IOException("Source changed during content observation");
            var entry=new Entry(after,hash);synchronized(ENTRIES){ENTRIES.put(file,entry);}future.complete(entry);return hash;
        } catch(IOException|RuntimeException|Error error){future.completeExceptionally(error);throw error;}
        finally {PENDING.remove(file,future);}
    }
}
