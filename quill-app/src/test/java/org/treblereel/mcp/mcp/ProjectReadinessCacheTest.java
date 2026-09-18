package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ProjectReadinessCacheTest {

    private final Path first = Path.of("first").toAbsolutePath();

    @Test
    void reusesReadinessUntilTtlExpires() {
        AtomicLong now = new AtomicLong();
        AtomicInteger loads = new AtomicInteger();
        ProjectReadinessCache cache = new ProjectReadinessCache(
                Duration.ofSeconds(5), now::get);

        assertNull(cache.get(first, () -> {
            loads.incrementAndGet();
            return null;
        }));
        assertNull(cache.get(first, () -> {
            loads.incrementAndGet();
            return null;
        }));
        assertEquals(1, loads.get());

        now.addAndGet(Duration.ofSeconds(5).toNanos());
        assertNull(cache.get(first, () -> {
            loads.incrementAndGet();
            return null;
        }));
        assertEquals(2, loads.get());
    }

    @Test
    void explicitInvalidationAndRepositoryRemovalDiscardEntries() {
        AtomicInteger loads = new AtomicInteger();
        ProjectReadinessCache cache = new ProjectReadinessCache(Duration.ofMinutes(1));
        Path second = Path.of("second").toAbsolutePath();

        cache.get(first, () -> issue("first", loads.incrementAndGet()));
        cache.invalidate(first);
        cache.get(first, () -> issue("first", loads.incrementAndGet()));
        cache.get(second, () -> issue("second", loads.incrementAndGet()));
        assertEquals(3, loads.get());
        assertEquals(2, cache.size());

        cache.retain(Set.of(first));
        assertEquals(1, cache.size());
    }

    private ProjectRegistry.ProjectIssue issue(String name, int sequence) {
        return new ProjectRegistry.ProjectIssue(name, Path.of(name), "build_required", "maven",
                "load-" + sequence, "build", false);
    }
}
