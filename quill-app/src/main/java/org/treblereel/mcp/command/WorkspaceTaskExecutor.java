package org.treblereel.mcp.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs independent workspace operations with bounded parallelism and stable result ordering. */
final class WorkspaceTaskExecutor {

    private WorkspaceTaskExecutor() {}

    static <T> List<T> invokeAll(List<? extends Callable<T>> tasks, int parallelism) {
        if (parallelism < 1) throw new IllegalArgumentException("--jobs must be at least 1");
        if (tasks.isEmpty()) return List.of();

        int workers = Math.min(parallelism, tasks.size());
        AtomicInteger sequence = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(workers, task -> {
            Thread thread = new Thread(task,
                    "quill-workspace-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<T>> futures = executor.invokeAll(tasks);
            List<T> results = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException failure) {
                    throw new IllegalStateException(
                            "Parallel workspace processing failed", failure.getCause());
                }
            }
            return List.copyOf(results);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workspace processing was interrupted", interrupted);
        } finally {
            executor.shutdownNow();
        }
    }
}
