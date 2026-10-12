package io.methvin.watchservice;

import java.io.IOException;
import java.nio.file.WatchEvent;

/** A registered recursive provider that deliberately emits no notifications. */
public final class SilentMacOSWatchService extends MacOSXListeningWatchService {
    public SilentMacOSWatchService() { super(new Config() {}); }
    @Override public AbstractWatchKey register(WatchablePath path,
            Iterable<? extends WatchEvent.Kind<?>> events) throws IOException {
        checkOpen();
        return new AbstractWatchKey(this, path, events, 256);
    }
}
