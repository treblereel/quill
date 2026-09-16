package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ProjectIndexLock {

    private static final Map<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();
    static final String LOCK_FILE = "index.lock";

    private ProjectIndexLock() {}

    @FunctionalInterface
    interface Operation<T> {
        T run() throws IOException;
    }

    static <T> T withLock(Path root, Operation<T> operation) throws IOException {
        Path normalizedRoot = root.toRealPath().normalize();
        Object jvmLock = JVM_LOCKS.computeIfAbsent(normalizedRoot, ignored -> new Object());
        synchronized (jvmLock) {
            Path quillDir = normalizedRoot.resolve(".quill");
            Files.createDirectories(quillDir);
            try (FileChannel channel = FileChannel.open(quillDir.resolve(LOCK_FILE),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                return operation.run();
            }
        }
    }

    static <T> T withLockAndDeleteDirectory(Path root, Operation<T> operation) throws IOException {
        Path normalizedRoot = root.toRealPath().normalize();
        Object jvmLock = JVM_LOCKS.computeIfAbsent(normalizedRoot, ignored -> new Object());
        synchronized (jvmLock) {
            Path quillDir = normalizedRoot.resolve(".quill");
            Files.createDirectories(quillDir);
            Path lockFile = quillDir.resolve(LOCK_FILE);
            T result;
            try (FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                result = operation.run();
            }
            Files.deleteIfExists(lockFile);
            Files.deleteIfExists(quillDir);
            return result;
        }
    }
}
