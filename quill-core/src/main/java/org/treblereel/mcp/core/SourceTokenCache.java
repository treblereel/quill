package org.treblereel.mcp.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Content-addressed cache for exact source token counts. */
final class SourceTokenCache {

    private static final String FORMAT = "quill-source-token-cache-v1";
    private static final int MAX_ENTRIES = 1_000_000;
    private static final int MAX_WORKERS = 4;

    record Result(Map<Path, Integer> tokenCounts, int hits, int misses) {
        Result {
            tokenCounts = Collections.unmodifiableMap(new LinkedHashMap<>(tokenCounts));
        }
    }

    private record BatchResult(
            Map<Path, Integer> tokenCounts, Map<String, Integer> cacheEntries,
            int hits, int misses) {}

    private SourceTokenCache() {}

    static Result count(Collection<Path> sources, Path cachePath) {
        List<Path> orderedSources = List.copyOf(sources);
        if (orderedSources.isEmpty()) return new Result(Map.of(), 0, 0);

        Map<String, Integer> cached = cachePath != null ? read(cachePath) : Map.of();
        int workerCount = Math.min(orderedSources.size(), Math.min(MAX_WORKERS,
                Runtime.getRuntime().availableProcessors()));
        List<BatchResult> batches = workerCount == 1
                ? List.of(countBatch(orderedSources, cached))
                : countParallel(orderedSources, cached, workerCount);

        Map<Path, Integer> tokenCounts = new LinkedHashMap<>();
        Map<String, Integer> currentEntries = new HashMap<>();
        int hits = 0;
        int misses = 0;
        for (BatchResult batch : batches) {
            tokenCounts.putAll(batch.tokenCounts());
            currentEntries.putAll(batch.cacheEntries());
            hits += batch.hits();
            misses += batch.misses();
        }
        if (cachePath != null && (misses > 0 || !currentEntries.equals(cached))) {
            write(cachePath, currentEntries);
        }
        return new Result(tokenCounts, hits, misses);
    }

    private static List<BatchResult> countParallel(
            List<Path> sources, Map<String, Integer> cached, int workerCount) {
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        try {
            List<Future<BatchResult>> futures = new ArrayList<>(workerCount);
            int baseSize = sources.size() / workerCount;
            int remainder = sources.size() % workerCount;
            int start = 0;
            for (int i = 0; i < workerCount; i++) {
                int size = baseSize + (i < remainder ? 1 : 0);
                List<Path> batch = sources.subList(start, start + size);
                futures.add(executor.submit(() -> countBatch(batch, cached)));
                start += size;
            }
            List<BatchResult> results = new ArrayList<>(workerCount);
            for (Future<BatchResult> future : futures) results.add(future.get());
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Source token counting was interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Parallel source token counting failed", e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private static BatchResult countBatch(List<Path> sources, Map<String, Integer> cached) {
        Map<Path, Integer> tokenCounts = new LinkedHashMap<>();
        Map<String, Integer> cacheEntries = new HashMap<>();
        MessageDigest digest = sha256();
        int hits = 0;
        int misses = 0;
        for (Path source : sources) {
            try {
                byte[] content = Files.readAllBytes(source);
                String fingerprint = HexFormat.of().formatHex(digest.digest(content));
                Integer tokens = cached.get(fingerprint);
                if (tokens != null) {
                    hits++;
                } else {
                    tokens = TokenCounter.count(new String(content, StandardCharsets.UTF_8));
                    misses++;
                }
                tokenCounts.put(source, tokens);
                cacheEntries.put(fingerprint, tokens);
            } catch (IOException e) {
                tokenCounts.put(source, 0);
            }
        }
        return new BatchResult(tokenCounts, cacheEntries, hits, misses);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static Map<String, Integer> read(Path cachePath) {
        if (!Files.isRegularFile(cachePath)) return Map.of();
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(cachePath)))) {
            if (!FORMAT.equals(input.readUTF())) return Map.of();
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) return Map.of();
            Map<String, Integer> result = new HashMap<>(count);
            for (int i = 0; i < count; i++) {
                String fingerprint = input.readUTF();
                int tokens = input.readInt();
                if (fingerprint.length() != 64 || tokens < 0) return Map.of();
                result.put(fingerprint, tokens);
            }
            return result;
        } catch (IOException | RuntimeException ignored) {
            return Map.of();
        }
    }

    private static void write(Path cachePath, Map<String, Integer> entries) {
        Path temporary = null;
        try {
            Files.createDirectories(cachePath.getParent());
            temporary = Files.createTempFile(cachePath.getParent(), ".source-tokens-", ".tmp");
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeUTF(FORMAT);
                output.writeInt(entries.size());
                for (var entry : entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    output.writeUTF(entry.getKey());
                    output.writeInt(entry.getValue());
                }
            }
            try {
                Files.move(temporary, cachePath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, cachePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // The cache is optional; exact token counting remains correct without it.
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
