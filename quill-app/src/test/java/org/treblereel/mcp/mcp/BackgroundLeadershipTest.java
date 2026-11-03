package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackgroundLeadershipTest {

    @TempDir Path root;

    @Test
    void oneServerLeadsAndAFollowerTakesOverWhenItStops() throws Exception {
        try (var alone = new BackgroundLeadership(root)) {
            assertTrue(alone.tryLead(), "without .quill there is nothing to share");
            alone.announcePrepared();
        }
        Files.createDirectories(root.resolve(".quill"));
        assertTrue(Files.notExists(root.resolve(BackgroundLeadership.REVISION)));

        var leader = new BackgroundLeadership(root);
        try (var follower = new BackgroundLeadership(root)) {
            assertTrue(leader.tryLead());
            assertFalse(follower.tryLead());
            assertFalse(follower.leading());
            leader.announcePrepared();
            String first = Files.readString(root.resolve(BackgroundLeadership.REVISION));
            leader.announcePrepared();
            assertFalse(first.equals(Files.readString(root.resolve(BackgroundLeadership.REVISION))));
            follower.announcePrepared();
            assertFalse(follower.tryLead(), "a follower's announcement attempt changes nothing");

            leader.close();
            assertTrue(follower.tryLead());
            assertTrue(follower.leading());
        }
    }
}
