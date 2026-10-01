package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WorkspaceTaskExecutorTest {

    @Test
    void runsFourTasksConcurrentlyAndKeepsResultOrder() {
        CountDownLatch started = new CountDownLatch(4);
        List<Callable<Integer>> tasks = IntStream.range(0, 4)
                .<Callable<Integer>>mapToObj(index -> () -> {
                    started.countDown();
                    assertTrue(started.await(2, TimeUnit.SECONDS));
                    return index;
                })
                .toList();

        assertEquals(List.of(0, 1, 2, 3), WorkspaceTaskExecutor.invokeAll(tasks, 4));
    }
}
