package org.treblereel.mcp.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.IndexWriter;
import org.jboss.jandex.Indexer;

/** Content-addressed, fixed-shard cache for application Jandex indexes. */
final class ApplicationIndexCache {

    private static final String FORMAT = "quill-application-jandex-v1";
    private static final int SHARDS = 8;

    private ApplicationIndexCache() {}

    record Result(IndexView index, int hits, int shardCount) {}

    private record CachedShard(String fingerprint, byte[] index) {}

    static Result loadOrBuild(ClassFileSnapshot snapshot, Path cachePath) {
        if (cachePath == null) {
            return new Result(index(snapshot.entries()), 0, 1);
        }
        List<List<ClassFileSnapshot.Entry>> entries = buckets(snapshot);
        List<String> fingerprints = entries.stream()
                .map(ApplicationIndexCache::fingerprint)
                .toList();
        List<CachedShard> cached = read(cachePath);
        List<Index> indexes = new ArrayList<>(SHARDS);
        int hits = 0;
        for (int shard = 0; shard < SHARDS; shard++) {
            CachedShard candidate = cached != null ? cached.get(shard) : null;
            if (candidate != null && candidate.fingerprint().equals(fingerprints.get(shard))) {
                try {
                    indexes.add(readIndex(candidate.index()));
                    hits++;
                    continue;
                } catch (IOException | RuntimeException ignored) {
                    // Rebuild only the unreadable shard below.
                }
            }
            indexes.add(index(entries.get(shard)));
        }
        if (hits != SHARDS) write(cachePath, fingerprints, indexes);
        List<IndexView> views = new ArrayList<>(indexes);
        IndexView view = indexes.size() == 1 ? indexes.getFirst() : CompositeIndex.create(views);
        return new Result(view, hits, SHARDS);
    }

    private static List<List<ClassFileSnapshot.Entry>> buckets(ClassFileSnapshot snapshot) {
        List<List<ClassFileSnapshot.Entry>> result = new ArrayList<>(SHARDS);
        for (int i = 0; i < SHARDS; i++) result.add(new ArrayList<>());
        snapshot.entries().stream()
                .sorted((left, right) -> key(left).compareTo(key(right)))
                .forEach(entry -> result.get(Math.floorMod(relativePath(entry).hashCode(), SHARDS))
                        .add(entry));
        return result;
    }

    private static Index index(List<ClassFileSnapshot.Entry> entries) {
        Indexer indexer = new Indexer();
        for (ClassFileSnapshot.Entry entry : entries) {
            try {
                indexer.index(new ByteArrayInputStream(entry.bytecode()));
            } catch (IOException e) {
                throw new IllegalStateException("Could not index " + entry.path(), e);
            }
        }
        return indexer.complete();
    }

    private static String fingerprint(List<ClassFileSnapshot.Entry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(FORMAT.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (ClassFileSnapshot.Entry entry : entries) {
                digest.update(key(entry).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.bytecode());
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String key(ClassFileSnapshot.Entry entry) {
        return entry.classesDirectory() + "\u0000" + relativePath(entry);
    }

    private static String relativePath(ClassFileSnapshot.Entry entry) {
        return entry.classesDirectory().relativize(entry.path()).toString().replace('\\', '/');
    }

    private static List<CachedShard> read(Path cachePath) {
        if (cachePath == null || !Files.isRegularFile(cachePath)) return null;
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(cachePath)))) {
            if (!FORMAT.equals(input.readUTF()) || input.readInt() != SHARDS) return null;
            long cacheSize = Files.size(cachePath);
            List<CachedShard> result = new ArrayList<>(SHARDS);
            for (int i = 0; i < SHARDS; i++) {
                String fingerprint = input.readUTF();
                int length = input.readInt();
                if (length < 1 || length > cacheSize) return null;
                byte[] serialized = input.readNBytes(length);
                if (serialized.length != length) return null;
                result.add(new CachedShard(fingerprint, serialized));
            }
            return result;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static Index readIndex(byte[] serialized) throws IOException {
        return new IndexReader(new ByteArrayInputStream(serialized)).read();
    }

    private static void write(Path cachePath, List<String> fingerprints, List<Index> indexes) {
        if (cachePath == null) return;
        Path temporary = null;
        try {
            Files.createDirectories(cachePath.getParent());
            temporary = Files.createTempFile(cachePath.getParent(), ".application-jandex-", ".tmp");
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeUTF(FORMAT);
                output.writeInt(SHARDS);
                for (int i = 0; i < SHARDS; i++) {
                    ByteArrayOutputStream serialized = new ByteArrayOutputStream();
                    new IndexWriter(serialized).write(indexes.get(i));
                    output.writeUTF(fingerprints.get(i));
                    output.writeInt(serialized.size());
                    serialized.writeTo(output);
                }
            }
            try {
                Files.move(temporary, cachePath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, cachePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // The cache is optional; a complete index was already built in memory.
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup of an unpublished cache file.
                }
            }
        }
    }
}
