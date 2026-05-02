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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SqlRecorderHook}.
 *
 * <p>Surefire is configured at the project root with {@code parallel=all,threadCount=5},
 * so test methods can run concurrently. Each test therefore tracks only its own
 * recorder instances and asserts on those instances only — never on the global
 * active-set size — to avoid cross-test interference.
 */
public class SqlRecorderHookTest {

    private final List<SqlRecorder<?>> myRecorders = new ArrayList<SqlRecorder<?>>();

    @After
    public void cleanup() {
        for (SqlRecorder<?> recorder : myRecorders) {
            SqlRecorderHook.unregister(recorder);
        }
        myRecorders.clear();
    }

    private PersistenceSqlRecorder track(PersistenceSqlRecorder recorder) {
        myRecorders.add(recorder);
        return recorder;
    }

    @Test public void
    should_register_and_unregister_recorder() {

        PersistenceSqlRecorder recorder = track(new PersistenceSqlRecorder());

        SqlRecorderHook.register(recorder);
        assertThat(SqlRecorderHook.getActiveRecorders()).contains(recorder);

        SqlRecorderHook.unregister(recorder);
        assertThat(SqlRecorderHook.getActiveRecorders()).doesNotContain(recorder);
    }

    @Test public void
    double_register_should_be_idempotent() {

        PersistenceSqlRecorder recorder = track(new PersistenceSqlRecorder());

        SqlRecorderHook.register(recorder);
        SqlRecorderHook.register(recorder);

        int occurrences = 0;
        for (SqlRecorder<?> r : SqlRecorderHook.getActiveRecorders()) {
            if (r == recorder) {
                occurrences++;
            }
        }
        assertThat(occurrences).isEqualTo(1);
    }

    @Test public void
    double_unregister_should_be_safe() {

        PersistenceSqlRecorder recorder = track(new PersistenceSqlRecorder());
        SqlRecorderHook.register(recorder);

        SqlRecorderHook.unregister(recorder);
        SqlRecorderHook.unregister(recorder);

        assertThat(SqlRecorderHook.getActiveRecorders()).doesNotContain(recorder);
    }

    @Test public void
    get_active_recorders_returns_unmodifiable_view() {

        PersistenceSqlRecorder recorder = track(new PersistenceSqlRecorder());
        SqlRecorderHook.register(recorder);

        Set<SqlRecorder<?>> view = SqlRecorderHook.getActiveRecorders();

        try {
            view.clear();
            org.junit.Assert.fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test public void
    concurrent_register_unregister_should_not_throw() throws Exception {

        final int threads = 8;
        final int iterations = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        for (int t = 0; t < threads; t++) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < iterations; i++) {
                            PersistenceSqlRecorder recorder = new PersistenceSqlRecorder();
                            SqlRecorderHook.register(recorder);
                            for (SqlRecorder<?> r : SqlRecorderHook.getActiveRecorders()) {
                                r.hashCode();
                            }
                            SqlRecorderHook.unregister(recorder);
                        }
                    } catch (Throwable th) {
                        failure.set(th);
                    } finally {
                        done.countDown();
                    }
                }
            });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).isTrue();
        assertThat(failure.get()).isNull();
    }

}
