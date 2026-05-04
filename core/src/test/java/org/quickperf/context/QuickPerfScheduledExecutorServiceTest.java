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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies wrap behavior for all four {@code schedule*} overloads.
 *
 * <p>{@code schedule(Runnable)} and {@code schedule(Callable)} capture once at schedule
 * time and install/restore once at fire time.
 *
 * <p>{@code scheduleAtFixedRate} and {@code scheduleWithFixedDelay} capture once at
 * schedule time and install/restore <strong>per fire</strong>: each invocation gets its
 * own install/restore cycle reusing the same captured snapshot.
 */
public class QuickPerfScheduledExecutorServiceTest {

    @Rule public final QuickPerfContextTestLock lock = new QuickPerfContextTestLock();

    private List<ContextSnapshotProvider> originalProviders;
    private StubContextSnapshotProvider stub;
    private ScheduledExecutorService underlying;
    private ScheduledExecutorService wrapped;

    @Before public void setUp() {
        stub = new StubContextSnapshotProvider("A");
        originalProviders = QuickPerfContext.setProvidersForTesting(
                Arrays.<ContextSnapshotProvider>asList(stub));
        underlying = Executors.newSingleThreadScheduledExecutor();
        wrapped = QuickPerfContext.wrap(underlying);
    }

    @After public void tearDown() throws Exception {
        underlying.shutdownNow();
        underlying.awaitTermination(2, TimeUnit.SECONDS);
        QuickPerfContext.setProvidersForTesting(originalProviders);
    }

    @Test public void schedule_runnable_wraps_once() throws Exception {
        ScheduledFuture<?> f = wrapped.schedule(new Runnable() {
            @Override public void run() { /* ok */ }
        }, 0, TimeUnit.MILLISECONDS);
        f.get(2, TimeUnit.SECONDS);
        assertThat(stub.captureCount.get()).isEqualTo(1);
        assertThat(stub.installCount.get()).isEqualTo(1);
        assertThat(stub.restoreCount.get()).isEqualTo(1);
    }

    @Test public void schedule_callable_wraps_once() throws Exception {
        ScheduledFuture<String> f = wrapped.schedule(new Callable<String>() {
            @Override public String call() { return "ok"; }
        }, 0, TimeUnit.MILLISECONDS);
        assertThat(f.get(2, TimeUnit.SECONDS)).isEqualTo("ok");
        assertThat(stub.captureCount.get()).isEqualTo(1);
        assertThat(stub.installCount.get()).isEqualTo(1);
        assertThat(stub.restoreCount.get()).isEqualTo(1);
    }

    @Test public void schedule_at_fixed_rate_captures_once_installs_per_fire() throws Exception {
        final CountDownLatch threeFires = new CountDownLatch(3);
        ScheduledFuture<?> f = wrapped.scheduleAtFixedRate(new Runnable() {
            @Override public void run() { threeFires.countDown(); }
        }, 0, 50, TimeUnit.MILLISECONDS);
        assertThat(threeFires.await(3, TimeUnit.SECONDS)).isTrue();
        f.cancel(false);
        // Capture happened ONCE at schedule time.
        assertThat(stub.captureCount.get()).isEqualTo(1);
        // Install/restore happened at least three times (once per fire).
        assertThat(stub.installCount.get()).isGreaterThanOrEqualTo(3);
        assertThat(stub.restoreCount.get()).isGreaterThanOrEqualTo(3);
        // Restore count tracks install count modulo a still-running fire (cancelled here).
        assertThat(stub.restoreCount.get()).isEqualTo(stub.installCount.get());
    }

    @Test public void schedule_with_fixed_delay_captures_once_installs_per_fire() throws Exception {
        final CountDownLatch threeFires = new CountDownLatch(3);
        ScheduledFuture<?> f = wrapped.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { threeFires.countDown(); }
        }, 0, 50, TimeUnit.MILLISECONDS);
        assertThat(threeFires.await(3, TimeUnit.SECONDS)).isTrue();
        f.cancel(false);
        assertThat(stub.captureCount.get()).isEqualTo(1);
        assertThat(stub.installCount.get()).isGreaterThanOrEqualTo(3);
        assertThat(stub.restoreCount.get()).isEqualTo(stub.installCount.get());
    }

    @Test public void schedule_at_fixed_rate_with_no_capture_uses_unwrapped_runnable() throws Exception {
        stub.captureReturnsNull = true;
        final CountDownLatch oneFire = new CountDownLatch(1);
        ScheduledFuture<?> f = wrapped.scheduleAtFixedRate(new Runnable() {
            @Override public void run() { oneFire.countDown(); }
        }, 0, 50, TimeUnit.MILLISECONDS);
        assertThat(oneFire.await(2, TimeUnit.SECONDS)).isTrue();
        f.cancel(false);
        // captureReturnsNull → no install/restore should occur.
        assertThat(stub.installCount.get()).isZero();
        assertThat(stub.restoreCount.get()).isZero();
    }

}
