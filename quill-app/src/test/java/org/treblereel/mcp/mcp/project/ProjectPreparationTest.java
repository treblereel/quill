package org.treblereel.mcp.mcp.project;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.tools.ToolProvider;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.index.*;
import org.treblereel.mcp.workspace.ProjectScope;
import org.treblereel.mcp.workspace.SingleProjectScope;
import org.treblereel.mcp.mcp.change.ChangeSessionQueries;
import org.treblereel.mcp.mcp.change.ChangeVerificationQueries;
import org.treblereel.mcp.mcp.server.McpToolCatalog;
import org.treblereel.mcp.mcp.support.ProjectAvailabilityResponses;
import org.treblereel.mcp.mcp.tools.QuillTools;

class ProjectPreparationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test void committingIndexedDirtySourcesPublishesWithoutBuildAndKeepsOldLease() throws Exception {
        commitFixture();
        try (var registry = registry()) {
            registry.prewarm();
            var previous = available(registry);
            var oldId = previous.observations().freshness().indexId();
            byte[] classes = Files.readAllBytes(root.resolve("target/classes/Marker.class"));
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var output = System.err;
            java.util.concurrent.CompletableFuture<Void> pendingRead = null;
            try (var lease = previous.jdbi().open()) {
                System.setErr(new java.io.PrintStream(output) {
                    @Override public void println(String message) {
                        super.println(message);
                        if (message.startsWith("[quill] SQLite delta:")) {
                            entered.countDown();
                            try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                        }
                    }
                });
                try {
                    commit("Commit already indexed sources");
                    assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    var pending = registry.resolve();
                    assertPrevious(pending, previous, "refresh_pending");
                    assertEquals(oldId, lease.createQuery("SELECT value FROM metadata WHERE key='index_id'").mapTo(String.class).one());
                    pendingRead = registry.awaitPrepared(null);
                    assertFalse(pendingRead.isDone(), "Wait for publication, without holding the index lease lock");
                } finally { release.countDown(); System.setErr(output); }
                var current = await(() -> registry.resolve().projects().stream().filter(entry ->
                        !entry.observations().freshness().indexId().equals(oldId)
                        && !entry.observations().freshness().structureStale()).findFirst().orElse(null));
                pendingRead.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertFalse(current.observations().freshness().commitStale());
                assertTrue(current.observations().freshness().staleReasons().isEmpty());
                assertGraphPrepared(current);
                assertArrayEquals(classes, Files.readAllBytes(root.resolve("target/classes/Marker.class")));
                assertFalse(Files.exists(root.resolve(".quill/build-events")));
                var result = JSON.readTree(current.<String>read(reader -> new ChangeSessionQueries().snapshot(
                        reader, root, List.of("Marker"), "Verify postcommit refresh", 100, "summary", "verification")));
                assertEquals("complete", result.path("phase").asText(), result.toString());
                assertTrue(result.path("verification_receipt").path("verified").asBoolean(), result.toString());
                assertEquals(oldId, lease.createQuery("SELECT value FROM metadata WHERE key='index_id'").mapTo(String.class).one());
            }
        }
    }

    @Test void emptyCommitRefreshesOnceAndModifiedBytecodeOrTestSourcesCannotReuseProof() throws Exception {
        commitFixture();
        commit("Commit indexed source");
        assertTrue(ProjectUpdater.updateAfterCommit(root));
        Path published = ProjectIndexStore.findBestAvailableDb(root);
        assertFalse(ProjectUpdater.updateAfterCommit(root));
        assertEquals(published, ProjectIndexStore.findBestAvailableDb(root));
        try (var git = Git.open(root.toFile())) {
            git.commit().setAllowEmpty(true).setSign(false).setMessage("Empty commit")
                    .setAuthor("Fixture", "fixture@example.test").call();
        }
        assertTrue(ProjectUpdater.updateAfterCommit(root));
        Path afterEmpty = ProjectIndexStore.findBestAvailableDb(root);
        assertNotEquals(published, afterEmpty);
        Path test = root.resolve("src/test/java/MarkerTest.java");
        String before = Files.readString(test);
        var stamp = Files.getLastModifiedTime(test);
        Files.writeString(test, before + "// uncompiled test change\n");
        Files.setLastModifiedTime(test, stamp);
        commit("Uncompiled test change");
        assertFalse(ProjectUpdater.updateAfterCommit(root));
        assertEquals(afterEmpty, ProjectIndexStore.findBestAvailableDb(root));
        Files.writeString(test, before);
        Files.setLastModifiedTime(test, stamp);
        Path kotlin = root.resolve("src/main/java/Added.kt");
        Files.writeString(kotlin, "class Added");
        assertFalse(ProjectUpdater.updateAfterCommit(root));
        Files.delete(kotlin);
        Path classes = root.resolve("target/classes/Marker.class");
        byte[] bytes = Files.readAllBytes(classes);
        var modified = Files.getLastModifiedTime(classes);
        bytes[bytes.length - 1] ^= 1;
        Files.write(classes, bytes); Files.setLastModifiedTime(classes, modified);
        assertFalse(ProjectUpdater.updateAfterCommit(root));
        assertEquals(afterEmpty, ProjectIndexStore.findBestAvailableDb(root));
    }

    @Test void oversizedCapturedRootsCannotReplaceThePublishedGeneration() throws Exception {
        commitFixture();
        Path base = ProjectIndexStore.findBestAvailableDb(root);
        byte[] before = Files.readAllBytes(base);
        commit("Commit indexed sources");
        Files.writeString(root.resolve("target/quill-java-test.properties"), "#".repeat(4 * 1024 * 1024 + 1));
        assertThrows(IllegalStateException.class, () -> ProjectUpdater.updateAfterCommit(root));
        assertEquals(base, ProjectIndexStore.findBestAvailableDb(root));
        assertArrayEquals(before, Files.readAllBytes(base));
    }

    @Test void exportedMavenIntegrationTestRootsParticipateInCommitProof() throws Exception {
        commitFixture();
        Path integration = root.resolve("additional-tests/ExtraIT.java");
        Files.createDirectories(integration.getParent()); Files.writeString(integration, "class ExtraIT {}\n");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", root.resolve("target/test-classes").toString(), integration.toString()));
        Files.writeString(root.resolve("target/quill-java-integration-test.properties"),
                "sourceRoot.count=1\nsourceRoot.0=" + integration.getParent() + "\n");
        var initialized = ProjectInitializer.initializeLockedDetailed(root, true, true, ProjectIndexStore.findBestAvailableDb(root));
        assertTrue(initialized.successful(), initialized.diagnostic());
        Path base = ProjectIndexStore.findBestAvailableDb(root);
        var modified = Files.getLastModifiedTime(integration);
        Files.writeString(integration, "class ExtraIT { int changed; }\n");
        Files.setLastModifiedTime(integration, modified);
        commit("Commit uncompiled integration-test source");
        assertFalse(ProjectUpdater.updateAfterCommit(root));
        assertEquals(base, ProjectIndexStore.findBestAvailableDb(root));
    }

    @Test void committingUnbuiltResourceEditsOrDeletionsCannotReuseCompiledEvidence() throws Exception {
        commitFixture();
        Path resource = root.resolve("src/main/resources/application.properties");
        Files.createDirectories(resource.getParent()); Files.writeString(resource, "flag=old\n");
        Files.writeString(root.resolve("target/classes/application.properties"), "flag=old\n");
        var initialized = ProjectInitializer.initializeLockedDetailed(root, true, true, ProjectIndexStore.findBestAvailableDb(root));
        assertTrue(initialized.successful(), initialized.diagnostic());
        Path base = ProjectIndexStore.findBestAvailableDb(root);
        var modified = Files.getLastModifiedTime(resource);
        Files.writeString(resource, "flag=new\n"); Files.setLastModifiedTime(resource, modified);
        commit("Commit unbuilt resources");
        assertFalse(ProjectUpdater.updateAfterCommit(root));
        assertEquals(base, ProjectIndexStore.findBestAvailableDb(root));
        try (var registry = registry()) {
            registry.prewarm();
            var entry = available(registry);
            assertEquals("changed", entry.observations().sourceFreshness().path("state").asText());
            var build = JSON.readTree(entry.<String>read(reader -> BuildStatusInspector.inspect(root, reader, null)));
            assertEquals("build_required", build.path("status").asText());
        }
        Files.delete(resource); commit("Commit deleted resource");
        assertFalse(ProjectUpdater.updateAfterCommit(root));
        assertEquals(base, ProjectIndexStore.findBestAvailableDb(root));
    }

    @Test void cleanIndexPreservesBuildMarkerButDirtyUncompiledIndexCannotBecomeFreshByCommit() throws Exception {
        fixture();
        try (var git = Git.open(root.toFile())) {
            git.commit().setAllowEmpty(true).setSign(false).setMessage("Empty commit")
                    .setAuthor("Fixture", "fixture@example.test").call();
        }
        assertTrue(ProjectUpdater.updateAfterCommit(root));
        Path clean = ProjectIndexStore.findBestAvailableDb(root);
        String marker = org.treblereel.mcp.db.QuillDatabase.open(clean).withHandle(handle ->
                handle.createQuery("SELECT value FROM metadata WHERE key='compiled_before_index'").mapTo(String.class).one());
        assertEquals("false", marker);
        Files.writeString(root.resolve("src/main/java/Marker.java"), "class Marker { String value() { return \"bad\"; } }\n");
        var result = ProjectInitializer.initializeLockedDetailed(root, true, false, clean);
        assertTrue(result.successful(), result.diagnostic());
        Path uncompiled = ProjectIndexStore.findBestAvailableDb(root);
        commit("Commit indexed but uncompiled edit");
        assertFalse(ProjectUpdater.updateAfterCommit(root));
        assertEquals(uncompiled, ProjectIndexStore.findBestAvailableDb(root));
    }

    @Test void committedUncompiledEditNeverPublishesAsFresh() throws Exception {
        commitFixture();
        Path base = ProjectIndexStore.findBestAvailableDb(root);
        Files.writeString(root.resolve("src/main/java/Marker.java"), "class Marker { String value() { return \"bad\"; } }\n");
        commit("Commit uncompiled source edit");
        assertFalse(ProjectUpdater.updateAfterCommit(root));
        assertEquals(base, ProjectIndexStore.findBestAvailableDb(root));
        try (var registry = registry()) {
            registry.prewarm();
            var entry = available(registry);
            assertTrue(entry.observations().freshness().commitStale());
            assertTrue(entry.observations().freshness().structureStale());
            assertEquals("changed", entry.observations().sourceFreshness().path("state").asText());
            var result = JSON.readTree(entry.<String>read(reader -> new ChangeVerificationQueries()
                    .verifyChange(reader, root, List.of("Marker"), 20)));
            assertFalse(result.path("verified").asBoolean());
            assertEquals("build", result.path("build").path("action").asText());
        }
    }

    @Test void failedCommitRefreshLeavesOldGenerationAndAutomaticallyRecovers() throws Exception {
        commitFixture();
        try (var registry = registry()) {
            registry.prewarm();
            var previous = available(registry);
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var fail = new AtomicBoolean(true);
            var output = System.err;
            System.setErr(new java.io.PrintStream(output) {
                @Override public void println(String message) {
                    super.println(message);
                    if (message.startsWith("[quill] SQLite delta:") && fail.compareAndSet(true, false)) {
                        entered.countDown();
                        try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                        throw new IllegalStateException("Injected commit publication failure");
                    }
                }
            });
            try {
                commit("Commit already built code");
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                assertPrevious(registry.resolve(), previous, "refresh_pending");
                assertEquals(previous.generation().path().toRealPath(), ProjectIndexStore.findBestAvailableDb(root).toRealPath());
                release.countDown();
                var current = await(() -> registry.resolve().projects().stream().filter(entry ->
                        !entry.observations().freshness().commitStale()
                        && !entry.observations().freshness().structureStale()).findFirst().orElse(null));
                assertFalse(fail.get());
                assertNotEquals(previous.observations().freshness().indexId(), current.observations().freshness().indexId());
            } finally { fail.set(false); release.countDown(); System.setErr(output); }
        }
    }

    private void commitFixture() throws Exception {
        fixture();
        Files.writeString(root.resolve("src/main/java/Marker.java"), "class Marker { String value() { return \"two\"; } }\n");
        compile();
        ProjectUpdater.updateAfterSuccessfulBuild(root);
    }

    private void commit(String message) throws Exception {
        try (var git = Git.open(root.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage(message).setAuthor("Fixture", "fixture@example.test").call();
        }
    }

    @Test void closingDuringWatcherConstructionClosesThePartialSubscription() throws Exception {
        fixture();
        var scope = new SingleProjectScope(); scope.register(root);
        var constructing = new java.util.concurrent.CountDownLatch(1);
        var health = new java.util.concurrent.atomic.AtomicReference<Path>();
        KotlinDirectoryWatcher.Factory watcher = (roots, listener, visitor, barrier) -> {
            var subscription = KotlinDirectoryWatcher.DEFAULT.create(roots, listener, visitor, barrier);
            health.set(roots.stream().filter(p -> p.getFileName().toString().startsWith("quill-watch-health-")).findFirst().orElseThrow());
            constructing.countDown();
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException shutdown) { Thread.currentThread().interrupt(); }
            return subscription;
        };
        try (var registry = new ProjectRegistry(scope);
                var preparation = new ProjectPreparation(registry, scope, watcher)) {
            preparation.startAsync();
            assertTrue(constructing.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(5), preparation::close);
            assertTrue(preparation.initialPreparation().isCancelled());
            assertFalse(Files.exists(health.get()), "Closing startup must remove its watcher resources");
        }
    }

    @Test void backgroundStartupRetriesDiscoveryFailureAndPublishesPreparedQueries() throws Exception {
        fixture();
        var scope = new SingleProjectScope(); scope.register(root);
        var failing = new AtomicBoolean(true);
        try (var registry = new ProjectRegistry(() -> {
            if (failing.get()) throw new IllegalStateException("Injected discovery failure");
            return scope.snapshot();
        })) {
            var initial = registry.prewarmAsync();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> initial.get(5, java.util.concurrent.TimeUnit.SECONDS));
            var pending = registry.resolve();
            assertEquals("refresh_pending", pending.issues().getFirst().code());
            assertTrue(pending.issues().getFirst().message().contains("Injected discovery failure"));
            failing.set(false);
            var entry = available(registry);
            assertGraphPrepared(entry);
            assertEquals(2, entry.<Integer>read(reader -> reader.symbols.findAllClasses().size()));
            assertFalse(entry.observations().freshness().structureStale());
        }
    }

    @Test void startupPreparesQueriesAndPinnedObservationsDoNotRescanInputs() throws Exception {
        fixture();
        try (var registry = registry()) {
            registry.prewarm();
            var entry = available(registry);
            assertNotNull(entry.observations());
            assertGraphPrepared(entry);
            assertEquals("matching", entry.observations().sourceFreshness().path("state").asText());
            Path pom = root.resolve("pom.xml");
            Files.move(pom, root.resolve("pom.hidden"));
            try {
                String result = entry.read(reader -> {
                    assertSame(entry.observations().classes(), reader.symbols.findAllClasses());
                    assertEquals("matching", reader.sourceFreshness(() -> { throw new AssertionError("source scan in request"); }).path("state").asText());
                    assertEquals("fresh", reader.buildFreshness(() -> { throw new AssertionError("output scan in request"); }).path("state").asText());
                    return new ChangeSessionQueries().snapshot(reader, root, List.of("Marker"), "Change marker", 10, "summary", "verification");
                });
                var data = JSON.readTree(result);
                assertEquals("complete", data.path("phase").asText(), result);
                assertTrue(data.path("verification_receipt").path("verified").asBoolean(), result);
            } finally { Files.move(root.resolve("pom.hidden"), pom); }
        }
    }

    @Test void sameSizeTimestampSourceEditInvalidatesPreparedFreshness() throws Exception {
        fixture();
        try (var registry = registry()) {
            registry.prewarm();
            var previous = available(registry).observations();
            Path source = root.resolve("src/main/java/Marker.java");
            var modified = Files.getLastModifiedTime(source);
            Files.writeString(source, Files.readString(source).replace("one", "two"));
            Files.setLastModifiedTime(source, modified);
            var entry = await(() -> {
                var resolved = registry.resolve();
                if (resolved.projects().isEmpty()) return null;
                var current = resolved.projects().getFirst();
                return current.observations().sourceFreshness().path("state").asText().equals("changed") ? current : null;
            });
            assertEquals("changed", entry.observations().sourceFreshness().path("state").asText());
            var result = JSON.readTree(entry.<String>read(c -> new ChangeVerificationQueries().verifyChange(c, root, List.of("Marker"), 10)));
            assertFalse(result.path("verified").asBoolean());
            assertEquals("build", result.path("build").path("action").asText());
            assertEquals("classes_stale", result.path("build").path("reason").asText());
        }
    }

    @Test void sameSizeTimestampBytecodeRewriteInvalidatesGenerationEvidence() throws Exception {
        fixture();
        try (var registry = registry()) {
            registry.prewarm();
            var previous = available(registry).observations();
            Path file = root.resolve("target/classes/Marker.class");
            var modified = Files.getLastModifiedTime(file);
            byte[] bytes = Files.readAllBytes(file); bytes[bytes.length-1] ^= 1;
            Files.write(file, bytes); Files.setLastModifiedTime(file, modified);
            var entry = await(() -> registry.resolve().projects().stream().filter(p -> p.observations() != previous
                    && p.observations().freshness().staleReasons().contains("compiled_snapshot_changed")).findFirst().orElse(null));
            var result = JSON.readTree(entry.<String>read(c -> new ChangeVerificationQueries().verifyChange(c, root, List.of("Marker"), 10)));
            assertFalse(result.path("verified").asBoolean());
            assertTrue(result.path("build").path("index").path("project_fingerprint_changed").asBoolean());
        }
    }

    @Test void preparationWaitsForAMarkedBuildAndResumesWhenItEnds() throws Exception {
        fixture();
        try (var registry = registry()) {
            registry.prewarm();
            var previous = available(registry).observations();
            Path marker = Files.createDirectories(root.resolve(RunningBuilds.DIRECTORY)).resolve("build");
            Files.writeString(marker, "pid=" + ProcessHandle.current().pid() + "\nstarted=" + java.time.Instant.now() + "\n");
            Path file = root.resolve("target/classes/Marker.class");
            byte[] bytes = Files.readAllBytes(file); bytes[bytes.length-1] ^= 1;
            Files.write(file, bytes);
            var waiting = await(() -> registry.resolve().issues().stream()
                    .filter(issue -> issue.reason() == ProjectRegistry.ReadinessReason.BUILD_RUNNING).findFirst().orElse(null));
            assertEquals("build_running", waiting.stage());
            Thread.sleep(500);
            assertTrue(registry.resolve().projects().stream().allMatch(p -> p.observations() == previous
                    || p.observations().freshness().staleReasons().contains("previous_generation")), "nothing prepared mid-build");

            Files.delete(marker);
            await(() -> registry.resolve().projects().stream().filter(p -> p.observations() != previous
                    && p.observations().freshness().staleReasons().contains("compiled_snapshot_changed")).findFirst().orElse(null));
        }
    }

    @Test void aFollowerAnswersFromItsGenerationWhileTheLeaderIsBusy() throws Exception {
        fixture();
        var scope = new SingleProjectScope(); scope.register(root);
        // The leader queues the build result and never gets to it, as a busy server would.
        var jobs = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        try (var leader = new ProjectRegistry(scope, new BuildEventConsumer(jobs::add)); var follower = registry()) {
            leader.prewarm();
            available(leader);
            follower.prewarm();
            var previous = available(follower);
            assertFalse(follower.leads(root));

            var events = Files.createDirectories(root.resolve(".quill/build-events"));
            Files.writeString(events.resolve("event.json"), "{\"version\":3,\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":"
                    + System.currentTimeMillis() + ",\"captureScope\":\"exception_chain\",\"failureMessagesBase64\":[],\"diagnosticsBase64\":[]}");
            assertNotNull(jobs.poll(60, java.util.concurrent.TimeUnit.SECONDS), "the leader took the build result");
            await(() -> follower.resolve().issues().stream().anyMatch(i -> i.code().equals("refresh_pending")) ? Boolean.TRUE : null);

            follower.awaitPrepared(null).get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertPrevious(follower.resolve(), previous, "refresh_pending");
            assertFalse(leader.awaitPrepared(null).isDone(), "the leader still waits for its own work");
        }
    }

    @Test void aFollowerServesTheLeadersPreparationsAndTakesOverWhenItStops() throws Exception {
        fixture();
        var leader = registry();
        try (var follower = registry()) {
            leader.prewarm();
            available(leader);
            follower.prewarm();
            var previous = available(follower).observations();
            assertTrue(leader.leads(root));
            assertFalse(follower.leads(root));

            Path file = root.resolve("target/classes/Marker.class");
            byte[] bytes = Files.readAllBytes(file); bytes[bytes.length-1] ^= 1;
            Files.write(file, bytes);
            // The follower has no watcher of its own; the leader's announcement wakes it.
            await(() -> follower.resolve().projects().stream().filter(p -> p.observations() != previous
                    && p.observations().freshness().staleReasons().contains("compiled_snapshot_changed")).findFirst().orElse(null));

            leader.close();
            await(() -> follower.leads(root) ? Boolean.TRUE : null);
            available(follower);
        } finally {
            leader.close();
        }
    }

    @Test void newGenerationIsPreparedBeforeSwitchAndOldLeaseRemainsUsable() throws Exception {
        fixture();
        try (var registry = registry()) {
            registry.prewarm();
            var previous = available(registry);
            String oldId = previous.observations().metadata().get("index_id");
            try (var lease = previous.jdbi().open()) {
                String oldMetadata = lease.createQuery("SELECT value FROM metadata WHERE key='index_id'").mapTo(String.class).one();
                Files.writeString(root.resolve("src/main/java/Extra.java"), "class Extra {}\n");
                compile();
                var output = System.err;
                AtomicBoolean editedDuringIndexing = new AtomicBoolean();
                System.setErr(new java.io.PrintStream(output) {
                    @Override public void println(String message) {
                        super.println(message);
                        if (message.startsWith("[quill] SQLite delta:")) {
                            try { Files.writeString(root.resolve("README.md"), "Documentation edited after database write"); }
                            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                            editedDuringIndexing.set(true);
                        }
                    }
                });
                try { ProjectUpdater.updateAfterSuccessfulBuild(root); }
                finally { System.setErr(output); }
                assertTrue(editedDuringIndexing.get());
                var current = await(() -> {
                    var resolved = registry.resolve();
                    return resolved.projects().stream().filter(entry -> entry.observations() != null
                            && !oldId.equals(entry.observations().metadata().get("index_id"))).findFirst().orElse(null);
                });
                assertNotSame(previous.generation(), current.generation());
                assertGraphPrepared(current);
                assertEquals(3, current.<Integer>read(c -> c.symbols.findAllClasses().size()).intValue());
                assertEquals(oldMetadata, lease.createQuery("SELECT value FROM metadata WHERE key='index_id'").mapTo(String.class).one());
                assertEquals(oldId, previous.read(c -> c.index.getMetadata().get("index_id")));
                assertEquals(current.observations().metadata().get("index_id"), current.read(c -> c.index.getMetadata().get("index_id")));
                Path published = ProjectIndexStore.findBestAvailableDb(root);
                System.setErr(new java.io.PrintStream(output) {
                    @Override public void println(String message) {
                        super.println(message);
                        if (message.startsWith("[quill] SQLite delta:")) {
                            try { Files.writeString(root.resolve("src/main/java/Extra.java"), "class Extra { int changed; }\n"); }
                            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                        }
                    }
                });
                try {
                    var failed = assertThrows(IllegalStateException.class,
                            () -> ProjectUpdater.update(root, true, message -> {}));
                    assertTrue(failed.getMessage().contains("WORKTREE_CHANGED"), failed.getMessage());
                } finally { System.setErr(output); }
                assertEquals(published, ProjectIndexStore.findBestAvailableDb(root));
                assertEquals(oldMetadata, lease.createQuery("SELECT value FROM metadata WHERE key='index_id'").mapTo(String.class).one());
            }
        }
    }

    @Test void watcherFailureCannotProduceVerifiedEvidenceAndRecoveryReprepares() throws Exception {
        fixture();
        var scope = new SingleProjectScope(); scope.register(root);
        AtomicBoolean broken = new AtomicBoolean(true);
        try (var registry = new ProjectRegistry(scope);
             var preparation = new ProjectPreparation(registry, scope, (roots, listener, visitor, barrier) -> {
                 if (broken.get()) throw new java.io.IOException("injected subscription failure");
                 return KotlinDirectoryWatcher.DEFAULT.create(roots, listener, visitor, barrier);
             })) {
            preparation.start();
            var entry = preparation.resolve(null).projects().getFirst();
            assertTrue(entry.observations().freshness().structureStale());
            assertTrue(entry.observations().freshness().staleReasons().contains("input_watch_unavailable"));
            var result = JSON.readTree(entry.<String>read(c -> new ChangeVerificationQueries().verifyChange(c, root, List.of("Marker"), 10)));
            assertFalse(result.path("verified").asBoolean());
            broken.set(false);
            assertNotNull(await(() -> preparation.resolve(null).projects().stream()
                    .filter(p -> !p.observations().freshness().structureStale()).findFirst().orElse(null)));
        }
    }

    @Test void buildEventsRefreshWithoutAnInputWatcherAndRetainAnActiveLease() throws Exception {
        fixture();
        var scope = new SingleProjectScope(); scope.register(root);
        try (var registry = new ProjectRegistry(scope);
             var preparation = new ProjectPreparation(registry, scope, (roots, listener, visitor, barrier) -> {
                 throw new java.io.IOException("input watcher unavailable");
             })) {
            preparation.start();
            var previous = preparation.resolve(null).projects().getFirst();
            String oldId = previous.observations().metadata().get("index_id");
            try (var lease = previous.jdbi().open()) {
                for (String tool : List.of("maven", "gradle")) {
                    String priorId = oldId;
                    Files.writeString(root.resolve("src/main/java/Extra" + tool + ".java"), "class Extra" + tool + " {}\n");
                    compile();
                    Path events = Files.createDirectories(root.resolve(".quill/build-events"));
                    var event = JSON.createObjectNode().put("version", 3).put("buildTool", tool)
                            .put("successful", true).put("finishedAt", System.currentTimeMillis())
                            .put("captureScope", tool.equals("maven") ? "exception_chain" : "task_output");
                    event.putArray("failureMessagesBase64"); event.putArray("diagnosticsBase64");
                    JSON.writeValue(events.resolve(tool + ".json").toFile(), event);
                    var current = await(() -> preparation.resolve(null).projects().stream()
                            .filter(p -> !priorId.equals(p.observations().metadata().get("index_id")))
                            .findFirst().orElse(null));
                    oldId = current.observations().metadata().get("index_id");
                    assertTrue(current.observations().freshness().staleReasons().contains("input_watch_unavailable"));
                    assertEquals(tool, JSON.readTree(root.resolve(".quill/build-state.json").toFile()).path("buildTool").asText());
                    assertEquals(2, lease.createQuery("SELECT COUNT(*) FROM classes").mapTo(Integer.class).one());
                }
            }
        }
    }

    @Test void removingProjectRetiresItsPreparedOwnerWithoutClosingAnActiveLease() throws Exception {
        fixture();
        var projects = new java.util.concurrent.atomic.AtomicReference<>(new org.treblereel.mcp.workspace.ProjectScope.Snapshot(
                0, List.of(new org.treblereel.mcp.workspace.ProjectScope.Project("fixture", root))));
        try (var registry = new ProjectRegistry(projects::get)) {
            registry.prewarm();
            var previous = available(registry);
            try (var lease = previous.jdbi().open()) {
                projects.set(new org.treblereel.mcp.workspace.ProjectScope.Snapshot(1, List.of()));
                await(() -> registry.isEmpty() && registry.cachedDatabaseCount() == 0 ? Boolean.TRUE : null);
                assertTrue(registry.resolve().projects().isEmpty());
                assertEquals("ok", lease.createQuery("PRAGMA integrity_check").mapTo(String.class).one());
            }
        }
    }

    @Test void closingPreparationStopsFurtherPublicationAndLeavesBorrowedReaderUsable() throws Exception {
        fixture();
        var registry = registry(); registry.prewarm();
        var previous = available(registry);
        try (var lease = previous.jdbi().open()) {
            registry.close();
            assertThrows(IllegalStateException.class, registry::resolve);
            assertEquals("ok", lease.createQuery("PRAGMA integrity_check").mapTo(String.class).one());
        }
    }

    @Test void documentationEditsRefreshWorktreeWithoutInvalidatingInputEvidence() throws Exception {
        fixture();
        try (var registry = registry()) {
            registry.prewarm();
            var previous = available(registry);
            for (int i = 0; i < 10; i++) {
                Files.writeString(root.resolve("README.md"), "Documentation revision " + i);
                var current = registry.resolve();
                assertFalse(current.projects().isEmpty(), current.errors().toString());
                assertSame(previous.generation(), current.projects().getFirst().generation());
                assertFalse(current.projects().getFirst().observations().freshness().structureStale());
            }
            var current = await(() -> registry.resolve().projects().stream()
                    .filter(p -> p.observations().worktree().path("changes").valueStream()
                            .anyMatch(c -> c.path("path").asText().equals("README.md")))
                    .findFirst().orElse(null));
            assertSame(previous.observations().classes(), current.observations().classes());
            assertSame(previous.observations().buildStatus(), current.observations().buildStatus());
            assertTrue(current.observations().freshness().worktreeDirty());
            assertDoesNotThrow(() -> current.read(reader -> new ProjectDependencyQueries().getProjectDependencies(
                    reader, root, null, null, null, null, null, null, null, 10, 0)));
        }
    }

    @Test void preparationErrorIsReportedAndAutomaticallyRecoversIncludingAtStartup() throws Exception {
        fixture();
        var projects = new SingleProjectScope(); projects.register(root);
        AtomicBoolean broken = new AtomicBoolean(true);
        org.treblereel.mcp.workspace.ProjectScope scope = () -> {
            if (broken.get() && Thread.currentThread().getName().startsWith("quill-project-prepare"))
                throw new AssertionError("injected preparation failure");
            return projects.snapshot();
        };
        try (var registry = new ProjectRegistry(scope); var preparation = new ProjectPreparation(registry, scope)) {
            assertTimeoutPreemptively(Duration.ofSeconds(10), preparation::start);
            var failed = await(() -> {
                var result = preparation.resolve(null);
                return result.issues().stream().anyMatch(i -> i.code().equals("index_unavailable")) ? result : null;
            });
            assertTrue(failed.projects().isEmpty());
            var issue = JSON.readTree(ProjectAvailabilityResponses.error(failed.issues()));
            assertEquals("preparation_retry_scheduled", issue.path("reason").asText());
            assertEquals("wait", issue.path("action").asText());
            assertTrue(issue.path("retry_after_ms").asLong() > 0);
            assertTrue(issue.path("automatic_retry").asBoolean());
            assertTrue(failed.issues().stream().anyMatch(i -> i.code().equals("index_unavailable")
                    && i.message().contains("injected preparation failure")), failed.toString());
            broken.set(false);
            var recovered = await(() -> preparation.resolve(null).projects().stream()
                    .filter(p -> !p.observations().freshness().structureStale()).findFirst().orElse(null));
            assertFalse(recovered.observations().freshness().structureStale());
            assertTrue(preparation.resolve(null).issues().isEmpty());
            var lastGood = recovered;
            broken.set(true);
            Files.writeString(root.resolve(".quill/build-state.json"), "{\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":"
                    + System.currentTimeMillis() + ",\"diagnostics\":[],\"failureMessages\":[]}");
            await(() -> preparation.resolve(null).issues().stream()
                    .anyMatch(i -> i.code().equals("index_unavailable") && i.message().contains("injected preparation failure")) ? true : null);
            assertPrevious(preparation.resolve(null), lastGood, "index_unavailable");
            broken.set(false);
            assertNotNull(await(() -> preparation.resolve(null).projects().stream()
                    .filter(p -> !p.observations().freshness().staleReasons().contains("previous_generation"))
                    .findFirst().orElse(null)));
        }
    }

    @Test void treesGitIgnoresAreNotWatchedButBuildOutputsAre() throws Exception {
        fixture();
        Files.writeString(root.resolve(".gitignore"), "target/\n.quill/\nresults/**/wire/\n");
        Path wire = Files.createDirectories(root.resolve("results/run/wire"));
        var visited = java.util.concurrent.ConcurrentHashMap.<Path>newKeySet();
        var scope = new SingleProjectScope(); scope.register(root);
        try (var registry = new ProjectRegistry(scope);
             var preparation = new ProjectPreparation(registry, scope, (roots, listener, visitor, barrier) ->
                     KotlinDirectoryWatcher.DEFAULT.create(roots, listener, (start, onDirectory, onFile) ->
                             visitor.recursiveVisitFiles(start, directory -> { visited.add(directory.toRealPath()); onDirectory.call(directory); }, onFile), barrier))) {
            preparation.start();
            var before = await(() -> preparation.resolve(null).projects().stream()
                    .filter(p -> !p.observations().freshness().structureStale()).findFirst().orElse(null)).observations();
            assertTrue(visited.contains(root.resolve("target/classes").toRealPath()), "build outputs stay watched");
            assertTrue(visited.contains(root.resolve("results/run").toRealPath()));
            assertFalse(visited.contains(wire.toRealPath()), "an ignored tree must not be walked");
            assertTrue(visited.contains(root.resolve("src/main/java").toRealPath()));
        }
    }

    @Test void slowEventDeliveryLeavesTheWatcherUpAndEvidenceVerified() throws Exception {
        fixture();
        var scope = new SingleProjectScope(); scope.register(root);
        var subscriptions = new java.util.concurrent.atomic.AtomicInteger();
        var slow = new AtomicBoolean();
        try (var registry = new ProjectRegistry(scope);
             var preparation = new ProjectPreparation(registry, scope, (roots, listener, visitor, barrier) -> {
                 subscriptions.incrementAndGet();
                 // A loaded fseventsd delivers read barriers late; the stream itself is alive.
                 return KotlinDirectoryWatcher.DEFAULT.create(roots, new io.methvin.watcher.DirectoryChangeListener() {
                     @Override public void onEvent(io.methvin.watcher.DirectoryChangeEvent event) throws java.io.IOException {
                         if (slow.get() && event.path() != null && event.path().getFileName().toString().startsWith("read-")) {
                             try { Thread.sleep(800); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
                         }
                         listener.onEvent(event);
                     }
                     @Override public void onException(Exception error) { listener.onException(error); }
                 }, visitor, barrier);
             })) {
            preparation.start();
            await(() -> preparation.resolve(null).projects().stream()
                    .filter(p -> !p.observations().freshness().structureStale()).findFirst().orElse(null));
            slow.set(true);
            for (int i = 0; i < 4; i++) {
                var resolved = preparation.resolve(null);
                assertTrue(resolved.issues().isEmpty(), resolved.issues().toString());
                assertFalse(resolved.projects().getFirst().observations().freshness().structureStale(),
                        resolved.projects().getFirst().observations().freshness().staleReasons().toString());
            }
            assertEquals(1, subscriptions.get(), "a late delivery must not tear the subscription down");
        }
    }

    @Test void aProjectThatCannotBeObservedReportsItselfWithoutBlockingTheOthers() throws Exception {
        var parent = root;
        var broken = Files.createDirectory(parent.resolve("broken"));
        var gitLink = Files.writeString(broken.resolve(".git"), "gitdir: " + parent.resolve("missing-git-dir") + "\n");
        root = Files.createDirectory(parent.resolve("healthy"));
        fixture();
        var healthy = root;
        // The unobservable project comes first: before isolation it stopped the healthy one from ever being observed.
        ProjectScope scope = () -> new ProjectScope.Snapshot(1, List.of(
                new ProjectScope.Project("broken", broken), new ProjectScope.Project("healthy", healthy)));
        try (var registry = new ProjectRegistry(scope); var preparation = new ProjectPreparation(registry, scope)) {
            preparation.start();
            assertNotNull(await(() -> preparation.resolve("healthy").projects().stream()
                    .filter(p -> !p.observations().freshness().structureStale()).findFirst().orElse(null)));
            var failed = preparation.resolve("broken");
            assertTrue(failed.issues().stream().anyMatch(i -> i.project().equals("broken")
                    && i.code().equals("index_unavailable") && i.message().contains("cannot be observed")), failed.toString());
            Files.delete(gitLink);
            assertNotNull(await(() -> preparation.resolve("broken").issues().stream()
                    .noneMatch(i -> i.message().contains("cannot be observed")) ? Boolean.TRUE : null));
        }
    }

    @Test void aSlowNativeDeliveryBarrierCannotBlockRequestsOrLeaveEvidenceVerified() throws Exception {
        var parent = root;
        root = Files.createDirectory(parent.resolve("healthy"));
        fixture();
        var healthy = root;
        root = Files.createDirectory(parent.resolve("slow"));
        fixture();
        var slow = root.toRealPath();
        ProjectScope scope = () -> new ProjectScope.Snapshot(1, List.of(
                new ProjectScope.Project("slow", root), new ProjectScope.Project("healthy", healthy)));
        var blocked = new AtomicBoolean();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var registry = new ProjectRegistry(scope);
             var preparation = new ProjectPreparation(registry, scope, (roots, listener, visitor, barrier) -> {
                     barrier.accept(() -> {
                         if (blocked.get() && roots.contains(slow)) {
                             entered.countDown();
                             try { release.await(); }
                             catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                         }
                     });
                     return io.methvin.watcher.DirectoryWatcher.builder().paths(roots).listener(listener)
                             .fileTreeVisitor(visitor).fileHashing(false)
                             .watchService(FileSystems.getDefault().newWatchService()).build();
             })) {
            preparation.start();
            var availableBefore = await(() -> preparation.resolve("slow").projects().stream()
                    .filter(p -> !p.observations().freshness().structureStale()).findFirst().orElse(null));
            await(() -> preparation.resolve("healthy").projects().stream()
                    .filter(p -> !p.observations().freshness().structureStale()).findFirst().orElse(null));
            blocked.set(true);
            try {
                var pending = assertTimeoutPreemptively(Duration.ofSeconds(ProjectPreparation.READ_BARRIER_SECONDS + 2), () -> preparation.resolve(null));
                assertEquals(0, entered.getCount());
                var unaffected = pending.projects().stream().filter(p -> p.name().equals("healthy")).findFirst().orElseThrow();
                assertFalse(unaffected.observations().freshness().structureStale(), pending.toString());
                assertTrue(pending.issues().stream().noneMatch(i -> i.project().equals("healthy")), pending.toString());
                assertTrue(pending.issues().stream().anyMatch(i -> i.code().equals("refresh_pending")));
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertPrevious(preparation.resolve("slow"), availableBefore, "refresh_pending"));
            } finally { blocked.set(false); release.countDown(); }
            assertNotNull(await(() -> preparation.resolve("slow").projects().stream()
                    .filter(p -> !p.observations().freshness().structureStale()).findFirst().orElse(null)));
        } finally { release.countDown(); }
    }

    @Test void worktreeRefreshCannotKeepVerificationWhenItObservesAnIndexedInputChange() throws Exception {
        fixture();
        try (var registry = registry()) {
            registry.prewarm();
            var previous = available(registry);
            Files.writeString(root.resolve("src/test/java/MarkerTest.java"), "class MarkerTest { int changed; }\n");
            var observations = ProjectPreparation.captureWorktree(previous);
            assertTrue(observations.freshness().structureStale());
            assertTrue(observations.freshness().staleReasons().contains("indexed_inputs_changed"));
            assertSame(previous.observations().classes(), observations.classes());
            var current = new ProjectRegistry.ProjectEntry(previous.name(), previous.root(), previous.generation(), observations);
            var result = JSON.readTree(current.<String>read(reader -> new ChangeVerificationQueries().verifyChange(reader, root, List.of("Marker"), 10)));
            assertFalse(result.path("verified").asBoolean(), result.toString());
            Path custom = root.resolve("src/integration-test/java/MarkerIT.java");
            Files.createDirectories(custom.getParent()); Files.writeString(custom, "class MarkerIT {}\n");
            assertNotNull(await(() -> registry.resolve().projects().stream()
                    .filter(p -> p.observations().freshness().staleReasons().contains("indexed_inputs_changed"))
                    .findFirst().orElse(null)));
        }
    }

    @Test void startupCanServeThePublishedGenerationWhileInputsKeepChanging() throws Exception {
        fixture();
        var projects = new SingleProjectScope(); projects.register(root);
        var editing = new AtomicBoolean(true);
        var edits = new java.util.concurrent.atomic.AtomicInteger();
        org.treblereel.mcp.workspace.ProjectScope scope = () -> {
            if (editing.get() && Thread.currentThread().getName().startsWith("quill-project-prepare")) {
                try { Files.writeString(root.resolve("src/main/java/Marker.java"),
                        "class Marker { String value() { return \"one\"; } } // revision " + edits.incrementAndGet()); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
            return projects.snapshot();
        };
        try (var registry = new ProjectRegistry(scope)) {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { registry.prewarm(); });
            var result = registry.resolve();
            assertFalse(result.projects().isEmpty(), result.toString());
            var entry = result.projects().getFirst();
            assertTrue(edits.get() > 0);
            assertPrevious(result, entry, "refresh_pending");
            assertTrue(result.issues().getFirst().previousGenerationAvailable());
            assertEquals(2, entry.<Integer>read(reader -> reader.symbols.findAllClasses().size()));
        } finally { editing.set(false); }
    }

    @Test void startupDuringRefreshPreparesThePublishedGenerationInsteadOfWaitingForRefresh() throws Exception {
        fixture();
        var scope = new SingleProjectScope(); scope.register(root);
        var jobs = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        var events = Files.createDirectories(root.resolve(".quill/build-events"));
        Files.writeString(events.resolve("event.json"), "{\"version\":3,\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":"
                + System.currentTimeMillis() + ",\"captureScope\":\"exception_chain\",\"failureMessagesBase64\":[],\"diagnosticsBase64\":[]}");
        try (var registry = new ProjectRegistry(scope, new BuildEventConsumer(jobs::add))) {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> { registry.prewarm(); });
            var previous = registry.resolve().projects().getFirst();
            assertPrevious(registry.resolve(), previous, "refresh_pending");
            assertGraphPrepared(previous);
            assertEquals(2, previous.<Integer>read(reader -> reader.symbols.findAllClasses().size()));
            var job = jobs.poll(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(job); job.run();
            var current = await(() -> registry.resolve().projects().stream()
                    .filter(p -> !p.observations().freshness().staleReasons().contains("previous_generation"))
                    .findFirst().orElse(null));
            assertSame(previous.generation(), current.generation());
            assertFalse(current.observations().freshness().structureStale());
        }
    }

    @Test void queuedRunningFailedAndRecoveredRefreshKeepQueriesAvailableWithoutMixingGenerations() throws Exception {
        fixture();
        var scope = new SingleProjectScope(); scope.register(root);
        var jobs = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
        // Generations are the subject here, and a real watcher ties the outcome to FSEvents latency under load.
        KotlinDirectoryWatcher.Factory noWatcher = (roots, listener, visitor, barrier) -> {
            throw new java.io.IOException("input watcher unavailable");
        };
        try (var registry = new ProjectRegistry(scope, new BuildEventConsumer(jobs::add), noWatcher)) {
            registry.prewarm();
            var previous = available(registry);
            var tools = new QuillTools(registry);
            var args = java.util.Map.<String, Object>of("target", "MarkerTest", "method", "check", "direction", "outbound");
            var baseline = invoke(tools, "get_call_hierarchy", args);
            assertEquals(2, baseline.path("calls").size(), baseline.toString());
            Files.writeString(root.resolve("src/main/java/Extra.java"), "class Extra {}\n");
            compile();
            var events = Files.createDirectories(root.resolve(".quill/build-events"));
            Files.writeString(events.resolve("event.json"), "{\"version\":3,\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":"
                    + System.currentTimeMillis() + ",\"captureScope\":\"exception_chain\",\"failureMessagesBase64\":[],\"diagnosticsBase64\":[]}");
            var job = jobs.poll(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(job);
            assertPrevious(registry.resolve(), previous, "refresh_pending");
            assertTrue(previous.generation().matches(previous.generation().path()), "Serving owner must stay open");
            var buildState = root.resolve(".quill/build-state.json");
            byte[] successfulBuild = Files.readAllBytes(buildState);
            try {
                Files.writeString(buildState, "{\"buildTool\":\"maven\",\"successful\":false,\"finishedAt\":"
                        + System.currentTimeMillis() + ",\"diagnostics\":[],\"failureMessages\":[\"injected build failure\"]}");
                var blocked = invoke(tools, "change_session", java.util.Map.of("targets", List.of("Marker"),
                        "change", "Change marker", "limit", 100, "view", "verification"));
                assertEquals("blocked", blocked.path("phase").asText(), blocked.toString());
                assertFalse(blocked.path("verification_receipt").path("verified").asBoolean());
                assertEquals("fix_diagnostics", blocked.path("directive").path("primary_action").path("action").asText());
            } finally { Files.write(buildState, successfulBuild); }
            assertEquals(callEdges(baseline), callEdges(invoke(tools, "get_call_hierarchy", args)));
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var output = System.err;
            var fail = new AtomicBoolean(true);
            System.setErr(new java.io.PrintStream(output) {
                @Override public void println(String message) {
                    super.println(message);
                    if (message.startsWith("[quill] SQLite delta:")) {
                        entered.countDown();
                        try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("refresh not released"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                        if (fail.get()) throw new IllegalStateException("injected index refresh failure");
                    }
                }
            });
            try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
                 var lease = previous.jdbi().open()) {
                var refreshing = workers.submit(job);
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                try {
                    for (int i = 0; i < 10; i++) {
                        var result = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> invoke(tools, "get_call_hierarchy", args));
                        assertEquals(callEdges(baseline), callEdges(result));
                        assertEquals(previous.observations().freshness().indexId(), result.path("_meta").path("index_id").asText());
                        assertFalse(result.path("answer_complete").asBoolean());
                        assertTrue(result.path("_meta").path("structure_stale").asBoolean());
                        assertEquals("low", result.path("_meta").path("answer_confidence").asText());
                    }
                    Files.writeString(root.resolve("README.md"), "Documentation during indexing");
                    assertPrevious(registry.resolve(), previous, "refresh_pending");
                } finally { release.countDown(); }
                refreshing.get(10, java.util.concurrent.TimeUnit.SECONDS);
                var failed = await(() -> {
                    var result = registry.resolve();
                    return result.issues().stream().anyMatch(i -> i.code().equals("index_refresh_failed")) ? result : null;
                });
                assertPrevious(failed, previous, "index_refresh_failed");
                assertEquals(1000L, failed.issues().getFirst().retryAfterMs(), "Pending refresh must not inflate error backoff");
                assertEquals(1, registry.cachedDatabaseCount());
                assertTrue(previous.generation().matches(previous.generation().path()));
                assertEquals(callEdges(baseline), callEdges(invoke(tools, "get_call_hierarchy", args)));
                fail.set(false);
                var retry = jobs.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(retry, "Refresh should retry without another build");
                workers.submit(retry).get(10, java.util.concurrent.TimeUnit.SECONDS);
                var current = await(() -> registry.resolve().projects().stream()
                        .filter(p -> p.generation() != previous.generation()
                                && !p.observations().freshness().staleReasons().contains("previous_generation"))
                        .findFirst().orElse(null));
                assertEquals(3, current.<Integer>read(c -> c.symbols.findAllClasses().size()));
                assertEquals(callEdges(baseline), callEdges(invoke(tools, "get_call_hierarchy", args)));
                assertEquals(1, registry.cachedDatabaseCount());
                assertFalse(previous.generation().matches(previous.generation().path()), "Superseded owner must retire");
                assertEquals(2, lease.createQuery("SELECT COUNT(*) FROM classes").mapTo(Integer.class).one());
                assertEquals(2, previous.<Integer>read(c -> c.symbols.findAllClasses().size()));
                var verified = JSON.readTree(current.<String>read(c -> new ChangeSessionQueries().snapshot(
                        c, root, List.of("Marker"), "Change marker", 100, "summary", "verification")));
                // The recovered generation holds the build's evidence; only the absent watcher keeps it unverified.
                var evidence = verified.path("verification_receipt").path("observed_evidence");
                assertEquals("success", evidence.path("build_status").asText(), verified.toString());
                assertEquals("fresh", evidence.path("compiled_outputs").path("status").asText(), verified.toString());
                assertEquals(List.of("input_watch_unavailable"), current.observations().freshness().staleReasons());
                assertFalse(verified.path("verification_receipt").path("verified").asBoolean(), "Unwatched inputs must not verify");
            } finally { release.countDown(); System.setErr(output); }
        }
    }

    private void assertPrevious(ProjectRegistry.Resolution result, ProjectRegistry.ProjectEntry previous, String code) throws Exception {
        assertEquals(1, result.projects().size(), result.toString());
        var entry = result.projects().getFirst();
        assertSame(previous.generation(), entry.generation());
        assertSame(previous.observations().classes(), entry.observations().classes());
        assertTrue(entry.observations().freshness().staleReasons().contains("previous_generation"));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals(code)), result.toString());
        var verified = JSON.readTree(entry.<String>read(c -> new ChangeSessionQueries().snapshot(
                c, root, List.of("Marker"), "Change marker", 100, "summary", "verification")));
        assertFalse(verified.path("verification_receipt").path("verified").asBoolean(), verified.toString());
        assertFalse(verified.path("verification_receipt").path("observed_evidence").path("index_current").asBoolean());
        assertEquals("wait_for_refresh", verified.path("directive").path("primary_action").path("action").asText(), verified.toString());
        assertFalse(verified.path("directive").path("primary_action").has("preparation_command"));
        assertEquals("unknown", entry.observations().sourceFreshness().path("state").asText());
        entry.read(reader -> {
            assertSame(previous.observations().classes(), reader.symbols.findAllClasses());
            assertEquals("unknown", reader.sourceFreshness(() -> { throw new AssertionError("source scan during refresh"); }).path("state").asText());
            assertEquals("unknown", reader.buildFreshness(() -> { throw new AssertionError("output scan during refresh"); }).path("state").asText());
            return null;
        });
    }

    private com.fasterxml.jackson.databind.JsonNode callEdges(com.fasterxml.jackson.databind.JsonNode response) {
        var calls = response.path("calls").deepCopy();
        calls.forEach(call -> ((com.fasterxml.jackson.databind.node.ObjectNode) call).remove("source_evidence"));
        return calls;
    }

    private com.fasterxml.jackson.databind.JsonNode invoke(QuillTools tools, String name, java.util.Map<String, Object> args) throws Exception {
        var method = java.util.Arrays.stream(QuillTools.class.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        var result = McpToolCatalog.invokeMethod(tools, method, args);
        assertFalse(result.error(), result::text);
        return JSON.readTree(result.text());
    }

    private void assertGraphPrepared(ProjectRegistry.ProjectEntry entry) {
        entry.jdbi().setSqlLogger(new org.jdbi.v3.core.statement.SqlLogger() {
            @Override public void logBeforeExecution(org.jdbi.v3.core.statement.StatementContext context) {
                if (context.getRawSql().equals("SELECT * FROM classes WHERE lifecycle = 'current' AND origin != 'orphan_output' ORDER BY id"))
                    throw new AssertionError("Graph inventory loaded in a request after preparation");
            }
        });
        assertSame(entry.observations().classes(), entry.readWithGraph(reader -> reader.graph().classes));
    }

    private ProjectRegistry registry() { var registry = new ProjectRegistry(); registry.register(root); return registry; }
    private static ProjectRegistry.ProjectEntry available(ProjectRegistry registry) throws Exception {
        return await(() -> registry.resolve().projects().stream()
                .filter(p -> !p.observations().freshness().staleReasons().contains("previous_generation"))
                .findFirst().orElse(null));
    }
    private static <T> T await(java.util.function.Supplier<T> check) throws Exception {
        // Generous on purpose: each preparation waits for real FSEvents delivery, seconds late on a loaded machine.
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            T result = check.get(); if (result != null) return result;
            Thread.sleep(20);
        }
        throw new AssertionError("Preparation did not reach the expected revision");
    }
    private void compile() {
        var sources = new java.util.ArrayList<String>(List.of("-d",root.resolve("target/classes").toString()));
        try (var files = Files.list(root.resolve("src/main/java"))) { files.forEach(file -> sources.add(file.toString())); }
        catch (java.io.IOException failure) { throw new RuntimeException(failure); }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,sources.toArray(String[]::new)));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,
                "-cp", root.resolve("target/classes").toString(), "-d", root.resolve("target/test-classes").toString(),
                root.resolve("src/test/java/MarkerTest.java").toString()));
    }
    private void fixture() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>p</groupId><artifactId>prepared</artifactId><version>1</version></project>");
        Files.createDirectories(root.resolve("src/main/java")); Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(root.resolve("src/main/java/Marker.java"), "class Marker { String value() { return \"one\"; } }\n");
        Files.createDirectories(root.resolve("src/test/java")); Files.createDirectories(root.resolve("target/test-classes"));
        Files.writeString(root.resolve("src/test/java/MarkerTest.java"), "class MarkerTest { String check() { return new Marker().value(); } }\n");
        Files.writeString(root.resolve("target/quill-test-classpath.txt"), "");
        Files.writeString(root.resolve(".gitignore"), "target/\n.quill/\n");
        try (var git = Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("fixture").setAuthor("Fixture", "fixture@example.test").call();
        }
        compile(); Files.writeString(root.resolve("target/quill-classpath.txt"), "");
        var initialized = ProjectInitializer.initializeDetailed(root, true);
        assertTrue(initialized.successful(), initialized.diagnostic());
        Files.writeString(root.resolve(".quill/build-state.json"), "{\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":"+System.currentTimeMillis()+",\"diagnostics\":[],\"failureMessages\":[]}");
    }
}
