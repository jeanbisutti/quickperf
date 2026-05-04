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
package org.quickperf.sql;

import org.junit.After;
import org.junit.Test;
import org.quickperf.context.QuickPerfContext;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates that two simulated "test threads" each wrapping a task on the SAME
 * single-threaded worker pool do not contaminate each other's recorders. Each task
 * sees only the recorder that its own test thread registered.
 *
 * <p>This is the primary safety property exercised by PR2: install/restore discipline
 * on the worker thread guarantees that a wrap from test A is fully reverted before a
 * wrap from test B installs its own state, even when both wraps are submitted to the
 * same pooled worker thread.
 */
public class QuickPerfContextNoContaminationWithTwoTestsTest {

    @After
    public void tearDown() {
        SqlRecorderRegistry.INSTANCE.clear();
    }

    @Test public void
    two_sequential_wraps_on_same_worker_thread_do_not_leak_recorders_to_each_other() throws Exception {
        // Two recorder instances, each acting as the "test thread's" recorder.
        final SqlRecorder recorderA = new PersistenceSqlRecorder();
        final SqlRecorder recorderB = new PersistenceSqlRecorder();

        final ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Test A: register, wrap, submit, then unregister.
            final AtomicReference<Collection<SqlRecorder>> seenByA
                    = new AtomicReference<Collection<SqlRecorder>>();
            runOnSimulatedTestThread(new Runnable() {
                @Override public void run() {
                    SqlRecorderRegistry.INSTANCE.register(recorderA);
                    try {
                        Runnable task = new Runnable() {
                            @Override public void run() {
                                seenByA.set(SqlRecorderRegistry.INSTANCE.getSqlRecorders());
                            }
                        };
                        try {
                            pool.submit(QuickPerfContext.wrap(task)).get(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    } finally {
                        SqlRecorderRegistry.INSTANCE.unregister(recorderA);
                    }
                }
            });

            // Test B: register, wrap, submit, then unregister.
            final AtomicReference<Collection<SqlRecorder>> seenByB
                    = new AtomicReference<Collection<SqlRecorder>>();
            runOnSimulatedTestThread(new Runnable() {
                @Override public void run() {
                    SqlRecorderRegistry.INSTANCE.register(recorderB);
                    try {
                        Runnable task = new Runnable() {
                            @Override public void run() {
                                seenByB.set(SqlRecorderRegistry.INSTANCE.getSqlRecorders());
                            }
                        };
                        try {
                            pool.submit(QuickPerfContext.wrap(task)).get(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    } finally {
                        SqlRecorderRegistry.INSTANCE.unregister(recorderB);
                    }
                }
            });

            // No leakage: A's task saw only recorderA; B's task saw only recorderB.
            assertThat(seenByA.get()).contains(recorderA).doesNotContain(recorderB);
            assertThat(seenByB.get()).contains(recorderB).doesNotContain(recorderA);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static void runOnSimulatedTestThread(final Runnable body) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { body.run(); }
                catch (Throwable th) { failure.set(th); }
                finally { done.countDown(); }
            }
        }, "simulated-test-thread");
        t.start();
        done.await(10, TimeUnit.SECONDS);
        t.join();
        if (failure.get() != null) {
            throw new RuntimeException(failure.get());
        }
    }

}
