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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link SqlRecorderRegistry#installSnapshot(Map)} and
 * {@link SqlRecorderRegistry#restoreSnapshot(Object)} cooperate so that a worker thread
 * can temporarily adopt the test thread's recorders, then revert to its prior state.
 */
public class SqlRecorderRegistryInstallRestoreTest {

    @After
    public void tearDown() {
        SqlRecorderRegistry.INSTANCE.clear();
    }

    @Test public void
    install_replaces_per_thread_recorders_visible_to_get() throws Exception {
        final SqlRecorder testThreadRecorder = new PersistenceSqlRecorder();
        SqlRecorderRegistry.INSTANCE.register(testThreadRecorder);
        final Map<Class<? extends SqlRecorder>, SqlRecorder> snapshot
                = SqlRecorderRegistry.INSTANCE.snapshotForCurrentThread();

        final AtomicReference<Collection<SqlRecorder>> seenByWorker
                = new AtomicReference<Collection<SqlRecorder>>();
        final AtomicReference<Object> token = new AtomicReference<Object>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                token.set(SqlRecorderRegistry.INSTANCE.installSnapshot(snapshot));
                try {
                    seenByWorker.set(SqlRecorderRegistry.INSTANCE.getSqlRecorders());
                } finally {
                    SqlRecorderRegistry.INSTANCE.restoreSnapshot(token.get());
                }
            }
        }, "snapshot-install-worker");
        worker.start();
        worker.join();

        assertThat(seenByWorker.get()).contains(testThreadRecorder);
    }

    @Test public void
    restore_removes_entry_when_worker_had_none_originally() throws Exception {
        final SqlRecorder testThreadRecorder = new PersistenceSqlRecorder();
        SqlRecorderRegistry.INSTANCE.register(testThreadRecorder);
        final Map<Class<? extends SqlRecorder>, SqlRecorder> snapshot
                = SqlRecorderRegistry.INSTANCE.snapshotForCurrentThread();

        final AtomicReference<Map<Class<? extends SqlRecorder>, SqlRecorder>> snapshotAfterRestore
                = new AtomicReference<Map<Class<? extends SqlRecorder>, SqlRecorder>>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                Object token = SqlRecorderRegistry.INSTANCE.installSnapshot(snapshot);
                SqlRecorderRegistry.INSTANCE.restoreSnapshot(token);
                snapshotAfterRestore.set(SqlRecorderRegistry.INSTANCE.snapshotForCurrentThread());
            }
        }, "snapshot-restore-cleanup");
        worker.start();
        worker.join();

        // The worker had no recorders before install; after restore it should still have none.
        assertThat(snapshotAfterRestore.get()).isEmpty();
    }

    @Test public void
    install_on_empty_map_is_a_no_op_token() {
        // Empty / null inputs return the same sentinel and do NOT touch PER_THREAD_RECORDERS.
        Object token1 = SqlRecorderRegistry.INSTANCE.installSnapshot(
                Collections.<Class<? extends SqlRecorder>, SqlRecorder>emptyMap());
        Object token2 = SqlRecorderRegistry.INSTANCE.installSnapshot(null);

        assertThat(token1).isNotNull().isSameAs(token2);

        // Must not throw — restoring the no-op token is a no-op.
        SqlRecorderRegistry.INSTANCE.restoreSnapshot(token1);
        SqlRecorderRegistry.INSTANCE.restoreSnapshot(token2);
    }

    @Test public void
    restore_with_unrecognised_token_throws() {
        try {
            SqlRecorderRegistry.INSTANCE.restoreSnapshot("not a token");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("not a token");
            return;
        }
        org.assertj.core.api.Assertions.fail("IllegalArgumentException expected");
    }

    @Test public void
    install_then_restore_puts_back_workers_prior_recorders() throws Exception {
        final SqlRecorder testThreadRecorder = new PersistenceSqlRecorder();
        SqlRecorderRegistry.INSTANCE.register(testThreadRecorder);
        final Map<Class<? extends SqlRecorder>, SqlRecorder> snapshot
                = SqlRecorderRegistry.INSTANCE.snapshotForCurrentThread();

        // Worker registers its own recorder first, then a wrap installs the test
        // thread's snapshot, then restore puts back the worker's prior state.
        final AtomicReference<Map<Class<? extends SqlRecorder>, SqlRecorder>> duringInstall
                = new AtomicReference<Map<Class<? extends SqlRecorder>, SqlRecorder>>();
        final AtomicReference<Map<Class<? extends SqlRecorder>, SqlRecorder>> afterRestore
                = new AtomicReference<Map<Class<? extends SqlRecorder>, SqlRecorder>>();
        final SqlRecorder workerOwnRecorder = new PersistenceSqlRecorder();

        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                SqlRecorderRegistry.INSTANCE.register(workerOwnRecorder);
                Object token = SqlRecorderRegistry.INSTANCE.installSnapshot(snapshot);
                duringInstall.set(new HashMap<Class<? extends SqlRecorder>, SqlRecorder>(
                        SqlRecorderRegistry.INSTANCE.snapshotForCurrentThread()));
                SqlRecorderRegistry.INSTANCE.restoreSnapshot(token);
                afterRestore.set(new HashMap<Class<? extends SqlRecorder>, SqlRecorder>(
                        SqlRecorderRegistry.INSTANCE.snapshotForCurrentThread()));
                SqlRecorderRegistry.INSTANCE.unregister(workerOwnRecorder);
            }
        }, "snapshot-restore-prior");
        worker.start();
        worker.join();

        // During install, only the test thread's recorder is visible (replacement, not merge).
        assertThat(duringInstall.get().values()).containsExactly(testThreadRecorder);
        // After restore, the worker observes its own recorder again.
        assertThat(afterRestore.get().values()).containsExactly(workerOwnRecorder);
    }

}
