package org.treblereel.mcp.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Short-lived cache for filesystem-heavy compiled-output readiness checks. */
final class ProjectReadinessCache {

    private record Entry(
            Optional<ProjectRegistry.ProjectIssue> issue, long checkedAtNanos) {}

    private final long ttlNanos;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<Path, Entry> entries = new ConcurrentHashMap<>();

    ProjectReadinessCache(Duration ttl) {
        this(ttl, System::nanoTime);
    }

    ProjectReadinessCache(Duration ttl, LongSupplier nanoTime) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("Readiness cache TTL must be positive");
        }
        this.ttlNanos = ttl.toNanos();
        this.nanoTime = nanoTime;
    }

    ProjectRegistry.ProjectIssue get(
            Path projectRoot, Supplier<ProjectRegistry.ProjectIssue> loader) {
        Path root = projectRoot.toAbsolutePath().normalize();
        long now = nanoTime.getAsLong();
        Entry entry = entries.compute(root, (ignored, current) -> {
            if (current != null && now - current.checkedAtNanos() < ttlNanos) return current;
            return new Entry(Optional.ofNullable(loader.get()), now);
        });
        return entry.issue().orElse(null);
    }

    void invalidate(Path projectRoot) {
        entries.remove(projectRoot.toAbsolutePath().normalize());
    }

    void clear() {
        entries.clear();
    }

    void retain(Set<Path> projectRoots) {
        entries.keySet().removeIf(root -> !projectRoots.contains(root));
    }

    int size() {
        return entries.size();
    }
}
