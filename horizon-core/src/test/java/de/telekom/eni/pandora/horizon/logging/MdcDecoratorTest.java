package de.telekom.eni.pandora.horizon.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class MdcDecoratorTest {

    @BeforeEach
    @AfterEach
    void resetMdc() {
        MDC.clear();
    }

    // --- Single-threaded Runnable tests ---

    @Test
    void runnable_appliesMdcDuringExecution() {
        AtomicReference<String> captured = new AtomicReference<>();
        Runnable decorated = MdcDecorator.withMdcContext(
                () -> captured.set(MDC.get("traceId")),
                Map.of("traceId", "123")
        );

        decorated.run();

        assertEquals("123", captured.get());
    }

    @Test
    void runnable_clearsMdcWhenPreviousMdcWasNull() {
        Runnable decorated = MdcDecorator.withMdcContext(() -> {}, Map.of("traceId", "123"));

        decorated.run();

        assertNull(MDC.getCopyOfContextMap());
    }

    @Test
    void runnable_restoresPreviousMdcWhenPresent() {
        MDC.setContextMap(Map.of("existing", "value"));
        Runnable decorated = MdcDecorator.withMdcContext(() -> {}, Map.of("traceId", "123"));

        decorated.run();

        assertEquals(Map.of("existing", "value"), MDC.getCopyOfContextMap());
    }

    @Test
    void runnable_restoresPreviousMdcWhenExceptionThrown() {
        MDC.setContextMap(Map.of("existing", "value"));
        Runnable decorated = MdcDecorator.withMdcContext(
                () -> { throw new IllegalStateException("boom"); },
                Map.of("traceId", "123")
        );

        assertThrows(IllegalStateException.class, decorated::run);
        assertEquals(Map.of("existing", "value"), MDC.getCopyOfContextMap());
    }

    @Test
    void runnable_clearsMdcWhenExceptionThrownAndPreviousMdcWasNull() {
        Runnable decorated = MdcDecorator.withMdcContext(
                () -> { throw new IllegalStateException("boom"); },
                Map.of("traceId", "123")
        );

        assertThrows(IllegalStateException.class, decorated::run);
        assertNull(MDC.getCopyOfContextMap());
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void runnable_throwsNpeAndRestoresMdcWhenRunnableIsNull() {
        MDC.setContextMap(Map.of("existing", "value"));
        Runnable decorated = MdcDecorator.withMdcContext((Runnable) null, Map.of("traceId", "123"));

        assertThrows(NullPointerException.class, decorated::run);
        assertEquals(Map.of("existing", "value"), MDC.getCopyOfContextMap());
    }

    @Test
    void runnable_overridesExistingMdcWithEmptyMapDuringExecution() {
        MDC.setContextMap(Map.of("existing", "value"));
        AtomicReference<Map<String, String>> captured = new AtomicReference<>();
        Runnable decorated = MdcDecorator.withMdcContext(
                () -> captured.set(MDC.getCopyOfContextMap()),
                Collections.emptyMap()
        );

        decorated.run();

        assertTrue(captured.get() == null || captured.get().isEmpty());
        assertEquals(Map.of("existing", "value"), MDC.getCopyOfContextMap());
    }

    // --- Single-threaded Function tests ---

    @Test
    void function_appliesMdcAndReturnsResult() {
        Function<String, String> decorated = MdcDecorator.withMdcContext(
                input -> input + ":" + MDC.get("traceId"),
                Map.of("traceId", "456")
        );

        assertEquals("ping:456", decorated.apply("ping"));
    }

    @Test
    void function_clearsMdcWhenPreviousMdcWasNull() {
        Function<String, String> decorated = MdcDecorator.withMdcContext(
                input -> input,
                Map.of("traceId", "456")
        );

        decorated.apply("ping");

        assertNull(MDC.getCopyOfContextMap());
    }

    @Test
    void function_restoresPreviousMdcWhenPresent() {
        MDC.setContextMap(Map.of("existing", "value"));
        Function<String, String> decorated = MdcDecorator.withMdcContext(
                input -> input,
                Map.of("traceId", "456")
        );

        decorated.apply("ping");

        assertEquals(Map.of("existing", "value"), MDC.getCopyOfContextMap());
    }

    @Test
    void function_restoresPreviousMdcWhenExceptionThrown() {
        MDC.setContextMap(Map.of("existing", "value"));
        Function<String, String> decorated = MdcDecorator.withMdcContext(
                input -> { throw new IllegalArgumentException("bad"); },
                Map.of("traceId", "456")
        );

        assertThrows(IllegalArgumentException.class, () -> decorated.apply("ping"));
        assertEquals(Map.of("existing", "value"), MDC.getCopyOfContextMap());
    }

    @Test
    void function_clearsMdcWhenExceptionThrownAndPreviousMdcWasNull() {
        Function<String, String> decorated = MdcDecorator.withMdcContext(
                input -> { throw new IllegalArgumentException("bad"); },
                Map.of("traceId", "456")
        );

        assertThrows(IllegalArgumentException.class, () -> decorated.apply("ping"));
        assertNull(MDC.getCopyOfContextMap());
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void function_throwsNpeAndRestoresMdcWhenFunctionIsNull() {
        MDC.setContextMap(Map.of("existing", "value"));
        Function<String, String> decorated = MdcDecorator.withMdcContext(
                (Function<String, String>) null,
                Map.of("traceId", "456")
        );

        assertThrows(NullPointerException.class, () -> decorated.apply("ping"));
        assertEquals(Map.of("existing", "value"), MDC.getCopyOfContextMap());
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void function_handlesNullInputAndNullReturn() {
        Function<String, String> decorated = MdcDecorator.withMdcContext(
                input -> null,
                Map.of("traceId", "456")
        );

        assertNull(decorated.apply(null));
        assertNull(MDC.getCopyOfContextMap());
    }

    // --- Multithreaded ThreadLocal MDC tests ---

    @Test
    void multithreaded_runnablePropagatesCallerMdcToWorkerThread() throws Exception {
        MDC.setContextMap(Map.of("traceId", "caller-123"));
        AtomicReference<Map<String, String>> workerMdc = new AtomicReference<>();
        Runnable decorated = MdcDecorator.withMdcContext(
                () -> workerMdc.set(MDC.getCopyOfContextMap()),
                MDC.getCopyOfContextMap()
        );

        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertEquals(Map.of("traceId", "caller-123"), workerMdc.get());
    }

    @Test
    void multithreaded_functionPropagatesCallerMdcToWorkerThread() throws Exception {
        MDC.setContextMap(Map.of("traceId", "caller-456"));
        Function<String, String> decorated = MdcDecorator.withMdcContext(
                input -> input + ":" + MDC.get("traceId"),
                MDC.getCopyOfContextMap()
        );

        String result = CompletableFuture.supplyAsync(() -> decorated.apply("req")).get();

        assertEquals("req:caller-456", result);
    }

    @Test
    void multithreaded_workerExecutionDoesNotModifyCallerThreadMdc() throws Exception {
        MDC.setContextMap(Map.of("traceId", "caller-only"));
        Runnable decorated = MdcDecorator.withMdcContext(() -> {}, Map.of("traceId", "worker-only"));

        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertEquals(Map.of("traceId", "caller-only"), MDC.getCopyOfContextMap());
    }

    @Test
    void multithreaded_clearsWorkerThreadMdcBeforeThreadReuseInPool() throws Exception {
        try (var singleThreadExecutor = Executors.newSingleThreadExecutor()) {
            Runnable decorated = MdcDecorator.withMdcContext(() -> {}, Map.of("traceId", "123"));
            singleThreadExecutor.submit(decorated).get();

            Map<String, String> mdcOnNextTask = singleThreadExecutor.submit(MDC::getCopyOfContextMap).get();

            assertNull(mdcOnNextTask);
        }
    }

    @Test
    void multithreaded_restoresWorkerThreadsOwnPreviousMdcInsteadOfCallersMdc() throws Exception {
        MDC.setContextMap(Map.of("owner", "caller"));
        AtomicReference<Map<String, String>> workerAfterRun = new AtomicReference<>();
        Runnable decorated = MdcDecorator.withMdcContext(() -> {}, Map.of("traceId", "temp"));

        Thread worker = new Thread(() -> {
            MDC.setContextMap(Map.of("owner", "worker"));
            decorated.run();
            workerAfterRun.set(MDC.getCopyOfContextMap());
        });
        worker.start();
        worker.join();

        assertEquals(Map.of("owner", "worker"), workerAfterRun.get());
    }

    @Test
    void multithreaded_isolatesMdcBetweenConcurrentThreads() throws Exception {
        Map<String, String> mdc1 = Map.of("traceId", "t1");
        Map<String, String> mdc2 = Map.of("traceId", "t2");
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Map<String, String>> seenByT1 = new AtomicReference<>();
        AtomicReference<Map<String, String>> seenByT2 = new AtomicReference<>();

        Thread t1 = new Thread(MdcDecorator.withMdcContext(() -> {
            await(barrier);
            seenByT1.set(MDC.getCopyOfContextMap());
        }, mdc1));

        Thread t2 = new Thread(MdcDecorator.withMdcContext(() -> {
            await(barrier);
            seenByT2.set(MDC.getCopyOfContextMap());
        }, mdc2));

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertEquals(mdc1, seenByT1.get());
        assertEquals(mdc2, seenByT2.get());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void multithreaded_isolatesMdcBetweenCompletableStages() {
        Map<String, String> mdc1 = Map.of("traceId", "stage 1");
        Map<String, String> mdc2 = Map.of("traceId", "stage 2");
        AtomicReference<Map<String, String>> seenByS1 = new AtomicReference<>();
        AtomicReference<Map<String, String>> seenByS2 = new AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture
                    .runAsync(MdcDecorator.withMdcContext(() -> seenByS1.set(MDC.getCopyOfContextMap()), mdc1), executor)
                    .thenRun(MdcDecorator.withMdcContext(() -> seenByS2.set(MDC.getCopyOfContextMap()), mdc2))
                    .join();
        }

        assertEquals(seenByS1.get(), mdc1);
        assertEquals(seenByS2.get(), mdc2);
        assertNotEquals(seenByS1.get(), seenByS2.get());
    }

    @Nested
    class CompletableFuturePipelines {

        @Test
        void nonAsync_thenApplyAndThenRunOnCompletedFuturePreserveCallerMdc() {
            MDC.setContextMap(Map.of("traceId", "caller"));
            AtomicReference<String> applyMdc = new AtomicReference<>();
            AtomicReference<String> runMdc = new AtomicReference<>();

            CompletableFuture.completedFuture("start")
                    .thenApply(MdcDecorator.withMdcContext(val -> {
                        applyMdc.set(MDC.get("traceId"));
                        return val.toUpperCase();
                    }, Map.of("traceId", "stage-1")))
                    .thenRun(MdcDecorator.withMdcContext(
                            () -> runMdc.set(MDC.get("traceId")),
                            Map.of("traceId", "stage-2")
                    ))
                    .join();

            assertEquals("stage-1", applyMdc.get());
            assertEquals("stage-2", runMdc.get());
            assertEquals(Map.of("traceId", "caller"), MDC.getCopyOfContextMap());
        }

        @Test
        void async_runAsyncThenApplyAsyncThenRunAsyncPropagatesMdcAcrossPoolThreads() {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Map<String, String> mdc = Map.of("traceId", "async-pipeline");
                AtomicReference<Map<String, String>> step1Mdc = new AtomicReference<>();
                AtomicReference<Map<String, String>> step2Mdc = new AtomicReference<>();
                AtomicReference<Map<String, String>> step3Mdc = new AtomicReference<>();

                CompletableFuture.runAsync(
                                MdcDecorator.withMdcContext(() -> step1Mdc.set(MDC.getCopyOfContextMap()), mdc),
                                pool
                        )
                        .thenApplyAsync(
                                MdcDecorator.withMdcContext(v -> {
                                    step2Mdc.set(MDC.getCopyOfContextMap());
                                    return "done";
                                }, mdc),
                                pool
                        )
                        .thenRunAsync(
                                MdcDecorator.withMdcContext(() -> step3Mdc.set(MDC.getCopyOfContextMap()), mdc),
                                pool
                        )
                        .join();

                assertEquals(mdc, step1Mdc.get());
                assertEquals(mdc, step2Mdc.get());
                assertEquals(mdc, step3Mdc.get());
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        void nonAsync_undecoratedStageDoesNotInheritMdcFromPreviousDecoratedStage() {
            try (ExecutorService singleThread = Executors.newSingleThreadExecutor()) {
                Map<String, String> seenInUndecoratedStage = CompletableFuture
                        .supplyAsync(
                                () -> MdcDecorator.withMdcContext(Function.identity(), Map.of("traceId", "stage-1")).apply("ok"),
                                singleThread
                        )
                        .thenApply(val -> MDC.getCopyOfContextMap())
                        .join();

                assertNull(seenInUndecoratedStage);
            }
        }

        @Test
        void exceptionally_recoversWithMdcWhenUpstreamStageThrows() {
            Map<String, String> stageMdc = Map.of("traceId", "failing-stage");
            Map<String, String> recoveryMdc = Map.of("traceId", "recovery-stage");

            String result = CompletableFuture.completedFuture("input")
                    .thenApply(MdcDecorator.<String,String>withMdcContext((String in) -> {
                        throw new IllegalStateException("fail");
                    }, stageMdc))
                    .exceptionally(MdcDecorator.withMdcContext(
                            ex -> "recovered:" + MDC.get("traceId"),
                            recoveryMdc
                    ))
                    .join();

            assertEquals("recovered:recovery-stage", result);
            assertNull(MDC.getCopyOfContextMap());
        }

        @Test
        void exceptionallyAsync_recoversOnWorkerThreadWithMdcAndCleansUpThread() {
            try (ExecutorService singleThread = Executors.newSingleThreadExecutor()) {
                Map<String, String> recoveryMdc = Map.of("traceId", "async-recovery");

                String recovered = CompletableFuture.<String>failedFuture(new RuntimeException("boom"))
                        .exceptionallyAsync(
                                MdcDecorator.withMdcContext(ex -> MDC.get("traceId"), recoveryMdc),
                                singleThread
                        )
                        .join();

                Map<String, String> leakedMdc = singleThread.submit(MDC::getCopyOfContextMap).get();

                assertEquals("async-recovery", recovered);
                assertNull(leakedMdc);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        void exceptionally_isSkippedWhenUpstreamSucceedsAndLeavesMdcClean() {
            AtomicReference<Map<String, String>> recoveryMdc = new AtomicReference<>();

            String result = CompletableFuture.completedFuture("ok")
                    .thenApply(MdcDecorator.withMdcContext(
                            in -> in + ":" + MDC.get("traceId"),
                            Map.of("traceId", "happy-path")
                    ))
                    .exceptionally(MdcDecorator.withMdcContext(ex -> {
                        recoveryMdc.set(MDC.getCopyOfContextMap());
                        return "fallback";
                    }, Map.of("traceId", "unused")))
                    .join();

            assertEquals("ok:happy-path", result);
            assertNull(recoveryMdc.get());
            assertNull(MDC.getCopyOfContextMap());
        }

        @Test
        void thenComposeAsync_chainsNestedFutureWithDistinctStageMdc() {
            Map<String, String> outerMdc = Map.of("step", "outer");
            Map<String, String> innerMdc = Map.of("step", "inner");

            String combined = CompletableFuture.completedFuture("req")
                    .thenComposeAsync(MdcDecorator.withMdcContext(input -> {
                        String outerStep = MDC.get("step");
                        return CompletableFuture.supplyAsync(() -> input + ":" + outerStep)
                                .thenApplyAsync(MdcDecorator.withMdcContext(
                                        val -> val + "->" + MDC.get("step"),
                                        innerMdc
                                ));
                    }, outerMdc))
                    .join();

            assertEquals("req:outer->inner", combined);
        }

        @Test
        void nestedSynchronousComposeRestoresOuterMdcAfterInnerStageCompletes() {
            Map<String, String> outerMdc = Map.of("step", "outer");
            Map<String, String> innerMdc = Map.of("step", "inner");
            AtomicReference<String> outerMdcAfterInner = new AtomicReference<>();

            CompletableFuture.completedFuture("req")
                    .thenCompose(MdcDecorator.withMdcContext(input -> {
                        CompletableFuture<String> inner = CompletableFuture.completedFuture(input)
                                .thenApply(MdcDecorator.withMdcContext(v -> MDC.get("step"), innerMdc));
                        outerMdcAfterInner.set(MDC.get("step"));
                        return inner;
                    }, outerMdc))
                    .join();

            assertEquals("outer", outerMdcAfterInner.get());
            assertNull(MDC.getCopyOfContextMap());
        }
    }
}