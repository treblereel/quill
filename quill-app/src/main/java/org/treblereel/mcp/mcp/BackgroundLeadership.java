package org.treblereel.mcp.mcp;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.treblereel.mcp.db.IndexSchema;

/**
 * Which of the Quill servers sharing a project does its background work: watching inputs,
 * consuming build results, refreshing and repairing the index. The server holding an OS file
 * lock in {@code .quill} leads; the others answer queries from the published generations and
 * re-prepare when the leader announces a new prepared revision. The OS drops the lock when the
 * leader exits, so a follower takes over on its next attempt. Servers of different schema
 * versions cannot read each other's generations, so each version elects its own leader.
 */
final class BackgroundLeadership implements AutoCloseable {

    static final String LOCK = ".quill/leader-v" + IndexSchema.VERSION + ".lock";
    static final String REVISION = ".quill/leader-v" + IndexSchema.VERSION + ".revision";

    private final Path root;
    private FileChannel channel;
    private FileLock lock;
    /** Leading without a lock because the project has no {@code .quill} directory to coordinate in. */
    private boolean unshared;
    private long revision;

    BackgroundLeadership(Path root) {
        this.root = root;
    }

    /** Takes or keeps leadership when it is free; true while this server leads. */
    synchronized boolean tryLead() {
        if (lock != null && lock.isValid()) return true;
        if (!Files.isDirectory(root.resolve(".quill"))) return unshared = true;
        try {
            if (channel == null) channel = FileChannel.open(root.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
        } catch (IOException | OverlappingFileLockException unavailable) {
            lock = null;
        }
        if (lock == null) {
            // The channel stays open: on POSIX, closing any descriptor of the file would also drop
            // a lock this JVM holds through another one.
            unshared = false;
            return false;
        }
        unshared = false;
        return true;
    }

    synchronized boolean leading() {
        return unshared || lock != null && lock.isValid();
    }

    /** Tells followers that the leader prepared new input evidence. */
    synchronized void announcePrepared() {
        if (!leading() || unshared) return;
        try {
            Files.writeString(root.resolve(REVISION), ProcessHandle.current().pid() + ":" + ++revision, StandardCharsets.UTF_8);
        } catch (IOException unavailable) {
            // Followers still re-prepare on refs, build-state and Git changes.
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (lock != null && lock.isValid()) lock.release();
        } catch (IOException ignored) {
        }
        lock = null;
        closeChannel();
    }

    private void closeChannel() {
        if (channel == null) return;
        try {
            channel.close();
        } catch (IOException ignored) {
        }
        channel = null;
    }
}
