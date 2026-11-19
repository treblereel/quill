package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import org.treblereel.mcp.diagnostics.QuillLog;

/** SHA reuse only on local volumes with a nanosecond change-time and stable inode identity. */
final class SourceContentCache {
    private static final Set<String> LOCAL_VOLUMES=Set.of("apfs","hfs","ext4","xfs","btrfs","tmpfs","overlay");
    private static final ExecutorService VOLUME_PROBE=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"quill-volume-probe");thread.setDaemon(true);return thread;
    });
    private static final ConcurrentHashMap<Object,CompletableFuture<Boolean>> VOLUMES=new ConcurrentHashMap<>();
    private record Token(Object key,Object device,long size,FileTime modified,FileTime changed) {}
    private record Entry(Token token,String hash) {}
    /** Reused hashes of one kind of file, bounded so a large output tree cannot evict the other kind. */
    static final class Store {
        private final Map<Path,Entry> entries;
        private final ConcurrentHashMap<Path,CompletableFuture<Entry>> pending=new ConcurrentHashMap<>();
        Store(int capacity) {
            entries=new LinkedHashMap<>(16,.75f,true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Path,SourceContentCache.Entry> entry){return size()>capacity;}
            };
        }
    }
    private static final Store SOURCES=new Store(4096);
    /** Compiled classes; sized for large multi-module outputs. */
    static final Store CLASSES=new Store(1<<17);
    record Volume(Object device,boolean supported) {}
    static Volume volume(Path root) {
        try {
            Object device=Files.getAttribute(root,"unix:dev");
            if(VOLUMES.size()>128)VOLUMES.clear();
            var probe=VOLUMES.computeIfAbsent(device,ignored->CompletableFuture.supplyAsync(()->{
                try{return LOCAL_VOLUMES.contains(Files.getFileStore(root).type().toLowerCase(Locale.ROOT));}
                catch(IOException|RuntimeException unknown){QuillLog.swallowed(unknown); return false;}
            },VOLUME_PROBE));
            return awaitVolume(device,probe);
        }
        catch(IOException|UnsupportedOperationException|IllegalArgumentException unknown){return new Volume(null,false);}
    }
    // A stalled mount-table query must disable SHA reuse, not stall the caller.
    static Volume awaitVolume(Object device,CompletableFuture<Boolean> probe) {
        try{return new Volume(device,probe.get(50,TimeUnit.MILLISECONDS));}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return new Volume(device,false);}
        catch(ExecutionException|TimeoutException unknown){return new Volume(device,false);}
    }
    private static Token token(Path file) throws IOException {
        var attributes=Files.readAttributes(file,"unix:fileKey,dev,size,lastModifiedTime,ctime");
        return new Token(attributes.get("fileKey"),attributes.get("dev"),((Number)attributes.get("size")).longValue(),
                (FileTime)attributes.get("lastModifiedTime"),(FileTime)attributes.get("ctime"));
    }
    @FunctionalInterface interface Reader {String read(Path file) throws IOException;}
    static String hash(Path file,Volume volume) throws IOException {return hash(file,volume,SourceFileHashes::hash);}
    static String hash(Path file,Volume volume,Reader reader) throws IOException {return hash(file,volume,reader,SOURCES);}
    static String hash(Path file,Volume volume,Reader reader,Store store) throws IOException {
        if(!volume.supported())return reader.read(file);
        Token before;
        try {before=token(file);}catch(UnsupportedOperationException|IllegalArgumentException unsupported){return reader.read(file);}
        if(before.key()==null || !Objects.equals(volume.device(),before.device()) || before.changed().toInstant().getNano()==0)
            return reader.read(file);
        synchronized(store.entries){var entry=store.entries.get(file);if(entry!=null && entry.token().equals(before))return entry.hash();}
        var future=new CompletableFuture<Entry>();var existing=store.pending.putIfAbsent(file,future);
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
            var entry=new Entry(after,hash);synchronized(store.entries){store.entries.put(file,entry);}future.complete(entry);return hash;
        } catch(IOException|RuntimeException|Error error){future.completeExceptionally(error);throw error;}
        finally {store.pending.remove(file,future);}
    }
}
