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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test exercising {@link QuickPerfContext#wrap} through the
 * ServiceLoader-discovered {@link SqlRecorderContextSnapshotProvider}: the test thread
 * registers a recorder, wraps a Runnable, submits it to a single-threaded executor,
 * and the worker observes that recorder via the standard
 * {@link SqlRecorderRegistry#getSqlRecorders()} path.
 */
public class QuickPerfContextWorkerThreadAttributionTest {

    @After
    public void tearDown() {
        SqlRecorderRegistry.INSTANCE.clear();
    }

    @Test public void
    wrapped_runnable_propagates_test_thread_recorders_to_worker() throws Exception {
        final SqlRecorder testThreadRecorder = new PersistenceSqlRecorder();
        SqlRecorderRegistry.INSTANCE.register(testThreadRecorder);

        final AtomicReference<Collection<SqlRecorder>> workerView
                = new AtomicReference<Collection<SqlRecorder>>();
        Runnable task = new Runnable() {
            @Override public void run() {
                workerView.set(SqlRecorderRegistry.INSTANCE.getSqlRecorders());
            }
        };
        Runnable wrapped = QuickPerfContext.wrap(task);
        // The test thread has registered recorders, so wrap MUST return a wrapper.
        assertThat(wrapped).isNotSameAs(task);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(wrapped).get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }

        assertThat(workerView.get()).contains(testThreadRecorder);
    }

}
