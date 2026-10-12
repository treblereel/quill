/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.methvin.watchservice;

import static java.nio.file.StandardWatchEventKinds.*;
import com.sun.jna.*;
import io.methvin.watcher.PathUtils;
import io.methvin.watcher.hashing.*;
import io.methvin.watcher.visitor.FileTreeVisitor;
import io.methvin.watchservice.jna.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

/**
 * Quill's dispatch-queue lifecycle for directory-watcher 0.19.1's macOS provider.
 * Event reconciliation below is adapted from its Apache-2.0 MacOSXListeningWatchService.
 * The package permits interoperability with the library's package-private WatchKeys;
 * no library class is shadowed. Registration starts synchronously; close drains callbacks.
 */
public final class QuillMacOSWatchService extends MacOSXListeningWatchService {
    interface Platform {
        Pointer queue();
        FSEventStreamRef create(Path root, CarbonAPI.FSEventStreamCallback callback, Pointer queue);
        boolean start(FSEventStreamRef stream);
        void stop(FSEventStreamRef stream);
        void invalidate(FSEventStreamRef stream);
        void drain(Pointer queue);
        void release(FSEventStreamRef stream);
        void releaseQueue(Pointer queue);
    }
    private record Stream(FSEventStreamRef ref, CarbonAPI.FSEventStreamCallback callback) {}
    private final Platform platform;
    private final Pointer queue;
    private final FileHasher hasher;
    private final FileTreeVisitor visitor;
    private final int queueSize;
    private final List<Stream> streams = new ArrayList<>();
    private final List<MacOSXWatchKey> keys = new CopyOnWriteArrayList<>();
    private final Set<Path> roots = new HashSet<>();
    private final AtomicBoolean invalidated = new AtomicBoolean();
    private boolean disposed;

    public QuillMacOSWatchService(Config config) { this(config, new JnaPlatform(config)); }
    QuillMacOSWatchService(Config config, Platform platform) {
        super(config); this.platform = platform; this.queue = platform.queue();
        if (queue == null) throw new IllegalStateException("Could not create FSEvents dispatch queue");
        this.visitor = config.fileTreeVisitor(); this.queueSize = config.queueSize();
        var sequence = new AtomicLong();
        this.hasher = config.fileHasher() == null ? path -> FileHash.fromLong(sequence.incrementAndGet()) : config.fileHasher();
    }

    @Override public synchronized AbstractWatchKey register(WatchablePath path,
            Iterable<? extends WatchEvent.Kind<?>> events) throws IOException {
        checkOpen();
        if (invalidated.get()) throw new IOException("FSEvents root is no longer available");
        Path root = path.getFile().toAbsolutePath();
        var key = new MacOSXWatchKey(this, path, events, queueSize);
        if (roots.stream().anyMatch(root::startsWith)) return key;
        var hashes = PathUtils.createHashCodeMap(root, hasher, visitor);
        var callback = new MacOSXListeningCallback(key, hasher, visitor, hashes, root);
        // Callback never closes/drains its own serial queue. Invalid keys terminate
        // DirectoryWatcher's loop, whose owner then closes the entire subscription.
        callback.onClose(this::invalidateKeys);
        FSEventStreamRef stream = platform.create(root, callback, queue);
        if (stream == null || stream.getPointer() == null) throw new IOException("Could not create FSEvents stream");
        keys.add(key);
        try {
            if (!platform.start(stream)) throw new IOException("Could not start FSEvents stream");
        } catch (RuntimeException | LinkageError | IOException failure) {
            platform.invalidate(stream); platform.drain(queue); platform.release(stream); keys.remove(key);
            throw failure;
        }
        streams.add(new Stream(stream, callback)); roots.add(root);
        return key;
    }

    private void invalidateKeys() {
        invalidated.set(true);
        for (var key : keys) { key.cancel(); key.signal(); }
    }

    @Override public synchronized void close() {
        if (disposed) return;
        disposed = true; super.close();
        // Keep callbacks strongly reachable until no native work can still invoke them.
        for (var stream : streams) { platform.stop(stream.ref()); platform.invalidate(stream.ref()); }
        platform.drain(queue);
        for (var stream : streams) platform.release(stream.ref());
        platform.releaseQueue(queue);
        streams.clear(); keys.clear(); roots.clear();
    }

    public interface EventsAPI extends CarbonAPI {
        void FSEventStreamSetDispatchQueue(FSEventStreamRef stream, Pointer queue);
        void CFRelease(Pointer value);
    }
    public interface DispatchAPI extends Library {
        Pointer dispatch_queue_create(String label, Pointer attributes);
        void dispatch_sync_f(Pointer queue, Pointer context, Work function);
        void dispatch_release(Pointer queue);
        interface Work extends Callback { void invoke(Pointer context); }
    }
    private static final class NativeLibraries {
        static final EventsAPI EVENTS = Native.load("Carbon", EventsAPI.class);
        static final DispatchAPI DISPATCH = Native.load("System", DispatchAPI.class);
        static final DispatchAPI.Work BARRIER = context -> {};
    }
    private static final class JnaPlatform implements Platform {
        private final Config config;
        JnaPlatform(Config config) { this.config = config; }
        public Pointer queue() { return NativeLibraries.DISPATCH.dispatch_queue_create("quill-k2-fsevents", null); }
        public FSEventStreamRef create(Path root, CarbonAPI.FSEventStreamCallback callback, Pointer queue) {
            var api = NativeLibraries.EVENTS;
            char[] chars = root.toString().toCharArray();
            var string = api.CFStringCreateWithCharacters(null, chars, CFIndex.valueOf(chars.length));
            var paths = api.CFArrayCreate(null, new Pointer[]{string.getPointer()}, CFIndex.valueOf(1), null);
            try {
                int flags = 0x2; // NoDefer; no private Java CFRunLoop thread.
                if (config.fileHasher() == null || config.fileLevelEvents()) flags |= 0x10;
                var stream = api.FSEventStreamCreate(null, callback, null, paths, -1, config.latency(), flags);
                if (stream != null && stream.getPointer() != null) api.FSEventStreamSetDispatchQueue(stream, queue);
                return stream;
            } finally { api.CFRelease(paths.getPointer()); api.CFRelease(string.getPointer()); }
        }
        public boolean start(FSEventStreamRef stream) { return NativeLibraries.EVENTS.FSEventStreamStart(stream); }
        public void stop(FSEventStreamRef stream) { NativeLibraries.EVENTS.FSEventStreamStop(stream); }
        public void invalidate(FSEventStreamRef stream) { NativeLibraries.EVENTS.FSEventStreamInvalidate(stream); }
        public void drain(Pointer queue) { NativeLibraries.DISPATCH.dispatch_sync_f(queue, null, NativeLibraries.BARRIER); }
        public void release(FSEventStreamRef stream) { NativeLibraries.EVENTS.FSEventStreamRelease(stream); }
        public void releaseQueue(Pointer queue) { NativeLibraries.DISPATCH.dispatch_release(queue); }
    }
  private static class MacOSXListeningCallback implements CarbonAPI.FSEventStreamCallback {
    private final MacOSXWatchKey watchKey;
    private final SortedMap<Path, FileHash> hashCodeMap;
    private final FileHasher fileHasher;
    private final FileTreeVisitor fileTreeVisitor;
    private final Path realPath;
    private final Path absPath;
    private final int realPathSize;

    private Runnable onCloseCallback;

    private MacOSXListeningCallback(
        MacOSXWatchKey watchKey,
        FileHasher fileHasher,
        FileTreeVisitor fileTreeVisitor,
        SortedMap<Path, FileHash> hashCodeMap,
        Path absPath)
        throws IOException {
      this.watchKey = watchKey;
      this.hashCodeMap = hashCodeMap;
      this.fileHasher = fileHasher;
      this.fileTreeVisitor = fileTreeVisitor;
      this.realPath = absPath.toRealPath();
      this.absPath = absPath;
      this.realPathSize = realPath.toString().length() + 1;
    }

    public void onClose(Runnable callback) {
      this.onCloseCallback = callback;
    }

    @Override
    public void invoke(
        FSEventStreamRef streamRef,
        Pointer clientCallBackInfo,
        NativeLong numEvents,
        Pointer eventPaths,
        Pointer eventFlags /* array of unsigned int */,
        Pointer eventIds /* array of unsigned long */) {
      final int length = numEvents.intValue();

      for (String fileName : eventPaths.getStringArray(0, length)) {
        /*
         * Note: If file-level events are enabled, fileName will be an individual file
         * so we usually won't recurse. It is necessary to normalise the filename back
         * to what is used in the key roots. Because the watcher returns real paths, but
         * relative paths may have be used in the key roots;
         */

        // only substring, if there are child paths, else just return absPath
        Path path =
            fileName.length() + 1 != realPathSize
                ? absPath.resolve(fileName.substring(realPathSize))
                : absPath;
        final Set<Path> filesOnDisk;
        try {
          filesOnDisk = PathUtils.recursiveListFiles(fileTreeVisitor, path);
        } catch (IOException e) {
          throw new IllegalStateException("Could not recursively list files for " + path, e);
        }
        /*
         * We collect and process all actions for each category of created, modified and
         * deleted as it appears a first thread can start while a second thread can get
         * through faster. If we do the collection for each category in a second thread
         * can get to the processing of modifications before the first thread is
         * finished processing creates. In this case the modification will not be
         * reported correctly.
         *
         * NOTE: We are now using a hash to determine if a file is different because if
         * modifications happens closely together the last modified time is not granular
         * enough to be seen as a modification. This likely mitigates the issue I
         * originally saw where the ordering was incorrect but I will leave the
         * collection and processing of each category together.
         */

        for (Path file : findCreatedFiles(filesOnDisk)) {
          if (watchKey.isReportCreateEvents()) {
            watchKey.signalEvent(ENTRY_CREATE, file);
          }
        }

        for (Path file : findModifiedFiles(filesOnDisk)) {
          if (watchKey.isReportModifyEvents()) {
            watchKey.signalEvent(ENTRY_MODIFY, file);
          }
        }

        List<Path> deletedPaths = findDeletedFiles(path, filesOnDisk);

        if (hashCodeMap.isEmpty()) {
          // all underlying paths are gone, cancel the key.
          // This must happen before this event, so that DirectoryWatch also cleans up
          watchKey.cancel();
        }

        for (Path file : deletedPaths) {
          if (watchKey.isReportDeleteEvents()) {
            watchKey.signalEvent(ENTRY_DELETE, file);
          }
        }

        if (hashCodeMap.isEmpty()) {
          // all underlying paths are gone, so stop this service
          // These is separated here to go after, in case it throws an exception.
          try {
            onCloseCallback.run();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }
    }

    private List<Path> findModifiedFiles(Set<Path> filesOnDisk) {
      List<Path> modifiedFileList = new ArrayList<Path>();
      for (Path file : filesOnDisk) {
        FileHash storedHashCode = hashCodeMap.get(file);
        FileHash newHashCode = PathUtils.hash(fileHasher, file);
        if (newHashCode != null && !newHashCode.equals(storedHashCode)) {
          modifiedFileList.add(file);
          hashCodeMap.put(file, newHashCode);
        }
      }
      return modifiedFileList;
    }

    private List<Path> findCreatedFiles(Set<Path> filesOnDisk) {
      List<Path> createdFileList = new ArrayList<Path>();
      for (Path file : filesOnDisk) {
        if (!hashCodeMap.containsKey(file)) {
          FileHash hashCode = PathUtils.hash(fileHasher, file);
          if (hashCode != null) {
            createdFileList.add(file);
            hashCodeMap.put(file, hashCode);
          }
        }
      }
      return createdFileList;
    }

    private List<Path> findDeletedFiles(Path path, Set<Path> filesOnDisk) {
      List<Path> deletedFileList = new ArrayList<Path>();
      for (Path file : PathUtils.subtreePaths(hashCodeMap, path)) {
        if (file.startsWith(path) && !filesOnDisk.contains(file)) {
          deletedFileList.add(file);
          hashCodeMap.remove(file);
        }
      }
      return deletedFileList;
    }
  }
}
