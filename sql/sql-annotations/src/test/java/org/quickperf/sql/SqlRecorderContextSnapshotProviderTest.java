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
import org.quickperf.context.Snapshot;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the contract of {@link SqlRecorderContextSnapshotProvider}.
 *
 * <p>Two key invariants:
 * <ul>
 *   <li>{@code capture()} returns {@code null} when the calling thread has no
 *       registered recorders (so {@code QuickPerfContext.wrap} returns the input
 *       unchanged and the worker still hits {@code ACTIVE_RECORDERS} fallback).</li>
 *   <li>When state IS present, the returned {@link Snapshot}'s {@code install} /
 *       {@code restore} round-trip is observable by the worker thread.</li>
 * </ul>
 */
public class SqlRecorderContextSnapshotProviderTest {

    @After
    public void tearDown() {
        SqlRecorderRegistry.INSTANCE.clear();
    }

    @Test public void
    capture_returns_null_when_thread_has_no_recorders() {
        Snapshot snapshot = new SqlRecorderContextSnapshotProvider().capture();
        assertThat(snapshot).isNull();
    }

    @Test public void
    capture_returns_snapshot_when_thread_has_registered_recorder() throws Exception {
        final SqlRecorder testThreadRecorder = new PersistenceSqlRecorder();
        SqlRecorderRegistry.INSTANCE.register(testThreadRecorder);

        final Snapshot captured = new SqlRecorderContextSnapshotProvider().capture();
        assertThat(captured).isNotNull();

        final AtomicReference<Collection<SqlRecorder>> workerView
                = new AtomicReference<Collection<SqlRecorder>>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                Object token = captured.install();
                try {
                    workerView.set(SqlRecorderRegistry.INSTANCE.getSqlRecorders());
                } finally {
                    captured.restore(token);
                }
            }
        }, "provider-roundtrip");
        worker.start();
        worker.join();

        assertThat(workerView.get()).contains(testThreadRecorder);
    }

}
