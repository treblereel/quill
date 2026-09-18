package org.treblereel.mcp.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.treblereel.mcp.workspace.WorkspaceDiscovery;
import org.treblereel.mcp.workspace.WorkspaceManifest;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;

/** Dynamically reconciled project scope for an explicitly initialized workspace. */
public final class WorkspaceProjectScope implements ProjectScope {

    private static final long RECONCILE_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();

    private record State(Snapshot snapshot, String fingerprint, long checkedAtNanos) {}

    private final WorkspaceManifest manifest;
    private final AtomicReference<State> state = new AtomicReference<>(
            new State(new Snapshot(0, List.of()), "", 0));

    public WorkspaceProjectScope(Path workspaceRoot) {
        try {
            this.manifest = WorkspaceManifestStore.read(workspaceRoot);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Could not read workspace manifest: "
                    + failure.getMessage(), failure);
        }
        refresh();
    }

    @Override
    public Snapshot snapshot() {
        State current = state.get();
        if (System.nanoTime() - current.checkedAtNanos() >= RECONCILE_INTERVAL_NANOS) {
            reconcile(false);
        }
        return state.get().snapshot();
    }

    public Snapshot refresh() {
        return reconcile(true);
    }

    public Path root() {
        return manifest.root();
    }

    private synchronized Snapshot reconcile(boolean force) {
        State previous = state.get();
        long now = System.nanoTime();
        if (!force && now - previous.checkedAtNanos() < RECONCILE_INTERVAL_NANOS) {
            return previous.snapshot();
        }
        WorkspaceDiscovery.Result discovery = WorkspaceDiscovery.discover(manifest);
        if (discovery.fingerprint().equals(previous.fingerprint())
                && discovery.diagnostics().equals(previous.snapshot().diagnostics())) {
            state.set(new State(previous.snapshot(), previous.fingerprint(), now));
            return previous.snapshot();
        }
        List<Project> projects = discovery.repositories().stream()
                .map(repository -> new Project(repository.name(), repository.root()))
                .toList();
        long revision = previous.snapshot().revision() + 1;
        Snapshot snapshot = new Snapshot(revision, projects, discovery.diagnostics());
        state.set(new State(snapshot, discovery.fingerprint(), now));
        return snapshot;
    }
}
