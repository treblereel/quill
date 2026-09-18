package org.treblereel.mcp.workspace;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Cross-process lock protecting workspace publication and removal. */
public final class WorkspaceLock implements AutoCloseable {

    public static final String FILE = ".quill-workspace.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private WorkspaceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static WorkspaceLock tryAcquire(Path workspaceRoot) throws IOException {
        Path lockPath = workspaceRoot.toAbsolutePath().normalize().resolve(FILE);
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return null;
            }
            return new WorkspaceLock(channel, lock);
        } catch (OverlappingFileLockException unavailable) {
            channel.close();
            return null;
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
