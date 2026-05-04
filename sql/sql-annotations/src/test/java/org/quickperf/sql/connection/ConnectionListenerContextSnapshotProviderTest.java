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
import org.quickperf.context.Snapshot;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the contract of {@link ConnectionListenerContextSnapshotProvider}: capture
 * returns null when nothing is registered, otherwise the returned Snapshot's
 * install/restore round-trip is observable from a worker thread.
 */
public class ConnectionListenerContextSnapshotProviderTest {

    @After
    public void tearDown() {
        ConnectionListenerRegistry.INSTANCE.clear();
    }

    @Test public void
    capture_returns_null_when_thread_has_no_listeners() {
        Snapshot snapshot = new ConnectionListenerContextSnapshotProvider().capture();
        assertThat(snapshot).isNull();
    }

    @Test public void
    capture_returns_snapshot_when_thread_has_registered_listener() throws Exception {
        final ConnectionLeakListener testThreadListener = new ConnectionLeakListener();
        ConnectionListenerRegistry.INSTANCE.register(testThreadListener);

        final Snapshot captured = new ConnectionListenerContextSnapshotProvider().capture();
        assertThat(captured).isNotNull();

        final AtomicReference<Collection<ConnectionListener>> workerView
                = new AtomicReference<Collection<ConnectionListener>>();
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                Object token = captured.install();
                try {
                    workerView.set(ConnectionListenerRegistry.INSTANCE.getConnectionListeners());
                } finally {
                    captured.restore(token);
                }
            }
        }, "listener-provider-roundtrip");
        worker.start();
        worker.join();

        assertThat(workerView.get()).contains(testThreadListener);
    }

}
