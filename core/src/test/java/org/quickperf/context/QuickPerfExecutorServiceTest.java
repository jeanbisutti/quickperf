/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Copyright 2019-2022 the original author or authors.
 */
package org.quickperf.context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every task-bearing method of {@link ExecutorService} wraps its argument
 * before forwarding (one capture/install/restore per task) and that lifecycle methods
 * delegate to the underlying executor.
 */
public class QuickPerfExecutorServiceTest {

    @Rule public final QuickPerfContextTestLock lock = new QuickPerfContextTestLock();

    private List<ContextSnapshotProvider> originalProviders;
    private StubContextSnapshotProvider stub;
    private ExecutorService underlying;
    private ExecutorService wrapped;

    @Before public void setUp() {
        stub = new StubContextSnapshotProvider("A");
        originalProviders = QuickPerfContext.setProvidersForTesting(
                Arrays.<ContextSnapshotProvider>asList(stub));
        underlying = Executors.newSingleThreadExecutor();
        wrapped = QuickPerfContext.wrap(underlying);
    }

    @After public void tearDown() throws Exception {
        underlying.shutdownNow();
        underlying.awaitTermination(2, TimeUnit.SECONDS);
        QuickPerfContext.setProvidersForTesting(originalProviders);
    }

    private void assertOneCaptureInstallRestoreCycle() {
        assertThat(stub.captureCount.get()).isEqualTo(1);
        assertThat(stub.installCount.get()).isEqualTo(1);
        assertThat(stub.restoreCount.get()).isEqualTo(1);
    }

    @Test public void execute_wraps_runnable() throws Exception {
        final CountDownLatch ran = new CountDownLatch(1);
        wrapped.execute(new Runnable() {
            @Override public void run() { ran.countDown(); }
        });
        assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
        wrapped.shutdown();
        wrapped.awaitTermination(2, TimeUnit.SECONDS);
        assertOneCaptureInstallRestoreCycle();
    }

    @Test public void submit_runnable_wraps() throws Exception {
        Future<?> f = wrapped.submit(new Runnable() {
            @Override public void run() { /* ok */ }
        });
        f.get(2, TimeUnit.SECONDS);
        assertOneCaptureInstallRestoreCycle();
    }

    @Test public void submit_runnable_with_result_wraps() throws Exception {
        Future<String> f = wrapped.submit(new Runnable() {
            @Override public void run() { /* ok */ }
        }, "result");
        assertThat(f.get(2, TimeUnit.SECONDS)).isEqualTo("result");
        assertOneCaptureInstallRestoreCycle();
    }

    @Test public void submit_callable_wraps() throws Exception {
        Future<String> f = wrapped.submit(new Callable<String>() {
            @Override public String call() { return "ok"; }
        });
        assertThat(f.get(2, TimeUnit.SECONDS)).isEqualTo("ok");
        assertOneCaptureInstallRestoreCycle();
    }

    @Test public void invoke_all_wraps_each_callable() throws Exception {
        Callable<String> c1 = new Callable<String>() { @Override public String call() { return "a"; } };
        Callable<String> c2 = new Callable<String>() { @Override public String call() { return "b"; } };
        List<Future<String>> futures = wrapped.invokeAll(Arrays.asList(c1, c2));
        assertThat(futures.get(0).get(2, TimeUnit.SECONDS)).isEqualTo("a");
        assertThat(futures.get(1).get(2, TimeUnit.SECONDS)).isEqualTo("b");
        assertThat(stub.captureCount.get()).isEqualTo(2);
        assertThat(stub.installCount.get()).isEqualTo(2);
        assertThat(stub.restoreCount.get()).isEqualTo(2);
    }

    @Test public void invoke_all_with_timeout_wraps_each_callable() throws Exception {
        Callable<String> c1 = new Callable<String>() { @Override public String call() { return "a"; } };
        wrapped.invokeAll(Collections.singletonList(c1), 2, TimeUnit.SECONDS);
        assertThat(stub.captureCount.get()).isEqualTo(1);
        assertThat(stub.installCount.get()).isEqualTo(1);
        assertThat(stub.restoreCount.get()).isEqualTo(1);
    }

    @Test public void invoke_any_wraps_each_callable() throws Exception {
        Callable<String> c1 = new Callable<String>() { @Override public String call() { return "a"; } };
        Object result = wrapped.invokeAny(Collections.singletonList(c1));
        assertThat(result).isEqualTo("a");
        assertThat(stub.captureCount.get()).isEqualTo(1);
        assertThat(stub.installCount.get()).isEqualTo(1);
        assertThat(stub.restoreCount.get()).isEqualTo(1);
    }

    @Test public void invoke_any_with_timeout_wraps_each_callable() throws Exception {
        Callable<String> c1 = new Callable<String>() { @Override public String call() { return "a"; } };
        Object result = wrapped.invokeAny(Collections.singletonList(c1), 2, TimeUnit.SECONDS);
        assertThat(result).isEqualTo("a");
        assertThat(stub.captureCount.get()).isEqualTo(1);
    }

    @Test public void shutdown_now_returns_wrapped_runnables() throws Exception {
        // Saturate the executor with a blocking task, queue another behind it, then shutdownNow.
        ThreadPoolExecutor blockingPool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(10));
        ExecutorService localWrapped = QuickPerfContext.wrap((ExecutorService) blockingPool);
        try {
            final CountDownLatch hold = new CountDownLatch(1);
            localWrapped.execute(new Runnable() {
                @Override public void run() {
                    try { hold.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                }
            });
            localWrapped.execute(new Runnable() { @Override public void run() {} });

            List<Runnable> unstarted = localWrapped.shutdownNow();
            // The unstarted task is the wrapper Runnable, not the user's original.
            assertThat(unstarted).hasSize(1);
            assertThat(unstarted.get(0)).isInstanceOf(QuickPerfRunnable.class);
            hold.countDown();
        } finally {
            blockingPool.shutdownNow();
            blockingPool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test public void rejected_execution_propagates_after_shutdown() {
        wrapped.shutdown();
        try {
            wrapped.submit(new Runnable() { @Override public void run() {} });
        } catch (RejectedExecutionException expected) {
            // Capture happened (we wrapped before submit); install/restore did not.
            assertThat(stub.captureCount.get()).isEqualTo(1);
            assertThat(stub.installCount.get()).isZero();
            assertThat(stub.restoreCount.get()).isZero();
            return;
        }
        org.assertj.core.api.Assertions.fail("RejectedExecutionException expected");
    }

    @Test public void lifecycle_methods_delegate() throws Exception {
        assertThat(wrapped.isShutdown()).isFalse();
        wrapped.shutdown();
        assertThat(wrapped.isShutdown()).isTrue();
        assertThat(wrapped.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(wrapped.isTerminated()).isTrue();
    }

}
