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

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRITICAL test — locks in the PR1 worker-thread {@code ACTIVE_RECORDERS} broadcast
 * fallback against an accidental future "install empty snapshot on workers" regression.
 *
 * <p>Scenario: the test thread has registered a recorder (so
 * {@code ACTIVE_RECORDERS} contains it). A worker thread that is NOT a child of the
 * test thread runs a task; if PR2's wrap mistakenly installed an empty snapshot on
 * that worker, the worker would observe {@code perThread != null && perThread.isEmpty()}
 * and take the "test-thread fast path" returning an empty list — silently dropping
 * SQL. By contract, {@code installSnapshot(emptyMap)} returns a no-op token and does
 * NOT touch {@code PER_THREAD_RECORDERS}, so the worker still falls back to
 * {@code ACTIVE_RECORDERS} and finds the recorder.
 */
public class SqlRecorderRegistryEmptySnapshotPreservesActiveFallbackTest {

    @After
    public void tearDown() {
        SqlRecorderRegistry.INSTANCE.clear();
    }

    @Test public void
    empty_install_then_worker_still_sees_recorder_via_active_fallback() throws Exception {
        final SqlRecorder testThreadRecorder = new PersistenceSqlRecorder();
        SqlRecorderRegistry.INSTANCE.register(testThreadRecorder);

        final AtomicReference<Collection<SqlRecorder>> workerView
                = new AtomicReference<Collection<SqlRecorder>>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                // Simulate what wrap WOULD do if it installed an empty snapshot.
                Object token = SqlRecorderRegistry.INSTANCE.installSnapshot(
                        Collections.<Class<? extends SqlRecorder>, SqlRecorder>emptyMap());
                try {
                    workerView.set(SqlRecorderRegistry.INSTANCE.getSqlRecorders());
                } finally {
                    SqlRecorderRegistry.INSTANCE.restoreSnapshot(token);
                }
            }
        }, "active-fallback-preservation");
        worker.start();
        worker.join();

        // ACTIVE_RECORDERS broadcast fallback fires because empty install did
        // NOT short-circuit the worker's per-thread map.
        assertThat(workerView.get()).contains(testThreadRecorder);
    }

}
