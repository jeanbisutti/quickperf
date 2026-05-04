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
package org.quickperf.sql.connection;

import org.junit.After;
import org.junit.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link ConnectionListenerRegistry#installSnapshot(Map)} and
 * {@link ConnectionListenerRegistry#restoreSnapshot(Object)} cooperate.
 */
public class ConnectionListenerRegistryInstallRestoreTest {

    @After
    public void tearDown() {
        ConnectionListenerRegistry.INSTANCE.clear();
    }

    @Test public void
    install_replaces_per_thread_listeners_visible_to_get() throws Exception {
        final ConnectionLeakListener testThreadListener = new ConnectionLeakListener();
        ConnectionListenerRegistry.INSTANCE.register(testThreadListener);
        final Map<Class<? extends ConnectionListener>, ConnectionListener> snapshot
                = ConnectionListenerRegistry.INSTANCE.snapshotForCurrentThread();

        final AtomicReference<Collection<ConnectionListener>> seenByWorker
                = new AtomicReference<Collection<ConnectionListener>>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                Object token = ConnectionListenerRegistry.INSTANCE.installSnapshot(snapshot);
                try {
                    seenByWorker.set(ConnectionListenerRegistry.INSTANCE.getConnectionListeners());
                } finally {
                    ConnectionListenerRegistry.INSTANCE.restoreSnapshot(token);
                }
            }
        }, "listener-snapshot-install-worker");
        worker.start();
        worker.join();

        assertThat(seenByWorker.get()).contains(testThreadListener);
    }

    @Test public void
    restore_removes_entry_when_worker_had_none_originally() throws Exception {
        final ConnectionLeakListener testThreadListener = new ConnectionLeakListener();
        ConnectionListenerRegistry.INSTANCE.register(testThreadListener);
        final Map<Class<? extends ConnectionListener>, ConnectionListener> snapshot
                = ConnectionListenerRegistry.INSTANCE.snapshotForCurrentThread();

        final AtomicReference<Map<Class<? extends ConnectionListener>, ConnectionListener>> snapshotAfterRestore
                = new AtomicReference<Map<Class<? extends ConnectionListener>, ConnectionListener>>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                Object token = ConnectionListenerRegistry.INSTANCE.installSnapshot(snapshot);
                ConnectionListenerRegistry.INSTANCE.restoreSnapshot(token);
                snapshotAfterRestore.set(ConnectionListenerRegistry.INSTANCE.snapshotForCurrentThread());
            }
        }, "listener-snapshot-restore-cleanup");
        worker.start();
        worker.join();

        assertThat(snapshotAfterRestore.get()).isEmpty();
    }

    @Test public void
    install_on_empty_map_is_a_no_op_token() {
        Object token1 = ConnectionListenerRegistry.INSTANCE.installSnapshot(
                Collections.<Class<? extends ConnectionListener>, ConnectionListener>emptyMap());
        Object token2 = ConnectionListenerRegistry.INSTANCE.installSnapshot(null);

        assertThat(token1).isNotNull().isSameAs(token2);

        ConnectionListenerRegistry.INSTANCE.restoreSnapshot(token1);
        ConnectionListenerRegistry.INSTANCE.restoreSnapshot(token2);
    }

    @Test public void
    restore_with_unrecognised_token_throws() {
        try {
            ConnectionListenerRegistry.INSTANCE.restoreSnapshot("not a token");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("not a token");
            return;
        }
        org.assertj.core.api.Assertions.fail("IllegalArgumentException expected");
    }

    @Test public void
    install_then_restore_puts_back_workers_prior_listeners() throws Exception {
        final ConnectionLeakListener testThreadListener = new ConnectionLeakListener();
        ConnectionListenerRegistry.INSTANCE.register(testThreadListener);
        final Map<Class<? extends ConnectionListener>, ConnectionListener> snapshot
                = ConnectionListenerRegistry.INSTANCE.snapshotForCurrentThread();

        final AtomicReference<Map<Class<? extends ConnectionListener>, ConnectionListener>> duringInstall
                = new AtomicReference<Map<Class<? extends ConnectionListener>, ConnectionListener>>();
        final AtomicReference<Map<Class<? extends ConnectionListener>, ConnectionListener>> afterRestore
                = new AtomicReference<Map<Class<? extends ConnectionListener>, ConnectionListener>>();
        // Worker starts with a different listener type so it is distinguishable from the
        // test thread's ConnectionLeakListener.
        final TestOnlyListener workerOwnListener = new TestOnlyListener();

        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                ConnectionListenerRegistry.INSTANCE.register(workerOwnListener);
                Object token = ConnectionListenerRegistry.INSTANCE.installSnapshot(snapshot);
                duringInstall.set(new HashMap<Class<? extends ConnectionListener>, ConnectionListener>(
                        ConnectionListenerRegistry.INSTANCE.snapshotForCurrentThread()));
                ConnectionListenerRegistry.INSTANCE.restoreSnapshot(token);
                afterRestore.set(new HashMap<Class<? extends ConnectionListener>, ConnectionListener>(
                        ConnectionListenerRegistry.INSTANCE.snapshotForCurrentThread()));
                ConnectionListenerRegistry.unregister(workerOwnListener);
            }
        }, "listener-snapshot-restore-prior");
        worker.start();
        worker.join();

        assertThat(duringInstall.get().values()).containsExactly(testThreadListener);
        assertThat(afterRestore.get().values()).containsExactly(workerOwnListener);
    }

    /** Test-only ConnectionListener subclass used to give the worker a listener type
     *  distinct from {@link ConnectionLeakListener}. */
    private static final class TestOnlyListener extends ConnectionListener { }

}
